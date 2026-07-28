import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

const DEFAULTS = Object.freeze({
  channelId: "openclaw-weixin",
  endpoint: "http://127.0.0.1:8080/v1/chat/completions",
  providerId: "digitalperson",
  model: "shen-zhixia",
  apiKeyEnv: "PERSON_API_TOKEN",
  timeoutMs: 220_000,
  directMessagesOnly: true,
  dedupeTtlMs: 10 * 60_000,
  backendErrorMessage: "后端暂时不可用，请稍后再试。",
  unsupportedMessage: "暂时只能处理文字消息。",
});

const completedReplies = new Map();
const inFlightReplies = new Map();

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function nonBlankString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function positiveInteger(value, fallback, minimum, maximum) {
  return Number.isInteger(value) && value >= minimum && value <= maximum ? value : fallback;
}

function pluginSettings(api) {
  const raw = asObject(api.pluginConfig);
  return {
    channelId: nonBlankString(raw.channelId) ?? DEFAULTS.channelId,
    endpoint: nonBlankString(raw.endpoint) ?? DEFAULTS.endpoint,
    providerId: nonBlankString(raw.providerId) ?? DEFAULTS.providerId,
    model: nonBlankString(raw.model) ?? DEFAULTS.model,
    apiKey: nonBlankString(raw.apiKey),
    apiKeyEnv: nonBlankString(raw.apiKeyEnv) ?? DEFAULTS.apiKeyEnv,
    timeoutMs: positiveInteger(raw.timeoutMs, DEFAULTS.timeoutMs, 1_000, 300_000),
    directMessagesOnly:
      typeof raw.directMessagesOnly === "boolean"
        ? raw.directMessagesOnly
        : DEFAULTS.directMessagesOnly,
    dedupeTtlMs: positiveInteger(raw.dedupeTtlMs, DEFAULTS.dedupeTtlMs, 1_000, 3_600_000),
    backendErrorMessage:
      nonBlankString(raw.backendErrorMessage) ?? DEFAULTS.backendErrorMessage,
    unsupportedMessage: nonBlankString(raw.unsupportedMessage) ?? DEFAULTS.unsupportedMessage,
  };
}

function currentOpenClawConfig(api) {
  try {
    return asObject(api.runtime?.config?.current?.());
  } catch {
    return {};
  }
}

function resolveSecretString(value) {
  const direct = nonBlankString(value);
  if (direct) {
    const bracedEnvironmentReference = /^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/.exec(direct);
    if (bracedEnvironmentReference) {
      return nonBlankString(process.env[bracedEnvironmentReference[1]]);
    }
    const environmentReference = /^\$([A-Za-z_][A-Za-z0-9_]*)$/.exec(direct);
    return environmentReference
      ? nonBlankString(process.env[environmentReference[1]])
      : direct;
  }

  const object = asObject(value);
  const valueField = nonBlankString(object.value);
  if (valueField) {
    return valueField;
  }
  const source = nonBlankString(object.source);
  const id = nonBlankString(object.id);
  if (source === "env" && id) {
    return nonBlankString(process.env[id]);
  }
  const environmentName = nonBlankString(object.env) ?? nonBlankString(object.environment);
  return environmentName ? nonBlankString(process.env[environmentName]) : undefined;
}

function resolveApiKey(api, settings) {
  const explicit = resolveSecretString(settings.apiKey);
  if (explicit) {
    return explicit;
  }

  const environmentValue = nonBlankString(process.env[settings.apiKeyEnv]);
  if (environmentValue) {
    return environmentValue;
  }

  const config = currentOpenClawConfig(api);
  const models = asObject(config.models);
  const providers = asObject(models.providers);
  const provider = asObject(providers[settings.providerId]);
  return resolveSecretString(provider.apiKey);
}

function incomingChannel(event, context) {
  return nonBlankString(event.channel) ?? nonBlankString(context.channelId);
}

function messageText(event) {
  const candidates = [event.body, event.bodyForAgent, event.transcript, event.content];
  for (const candidate of candidates) {
    const text = nonBlankString(candidate);
    if (text) {
      return text;
    }
  }
  return undefined;
}

function messageKey(event, context) {
  const messageId = nonBlankString(event.messageId) ?? nonBlankString(context.messageId);
  if (!messageId) {
    return undefined;
  }
  return [
    incomingChannel(event, context) ?? "unknown-channel",
    nonBlankString(event.accountId) ?? nonBlankString(context.accountId) ?? "default",
    messageId,
  ].join(":");
}

function pruneCompletedReplies(now) {
  for (const [key, value] of completedReplies.entries()) {
    if (value.expiresAt <= now) {
      completedReplies.delete(key);
    }
  }
}

function assistantText(payload) {
  const choices = Array.isArray(payload?.choices) ? payload.choices : [];
  const content = choices[0]?.message?.content;
  if (typeof content === "string" && content.trim()) {
    return content.trim();
  }
  if (Array.isArray(content)) {
    const parts = content
      .map((part) => {
        if (typeof part === "string") {
          return part.trim();
        }
        if (part && typeof part === "object" && typeof part.text === "string") {
          return part.text.trim();
        }
        return "";
      })
      .filter(Boolean);
    if (parts.length > 0) {
      return parts.join("\n");
    }
  }
  throw new Error("Digital Person backend returned no assistant text");
}

async function requestDigitalPerson(api, settings, text, event, context) {
  const apiKey = resolveApiKey(api, settings);
  if (!apiKey) {
    throw new Error(
      `No API key found. Configure plugin apiKey/apiKeyEnv or models.providers.${settings.providerId}.apiKey`,
    );
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), settings.timeoutMs);
  try {
    const response = await fetch(settings.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        ...(event.messageId || context.messageId
          ? { "X-OpenClaw-Message-Id": String(event.messageId ?? context.messageId) }
          : {}),
      },
      body: JSON.stringify({
        model: settings.model,
        stream: false,
        messages: [{ role: "user", content: text }],
      }),
      signal: controller.signal,
    });

    const responseText = await response.text();
    if (!response.ok) {
      throw new Error(
        `Digital Person backend HTTP ${response.status}: ${responseText.slice(0, 500)}`,
      );
    }

    let payload;
    try {
      payload = JSON.parse(responseText);
    } catch (error) {
      throw new Error(
        `Digital Person backend returned invalid JSON: ${
          error instanceof Error ? error.message : String(error)
        }`,
      );
    }
    return assistantText(payload);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(`Digital Person backend timed out after ${settings.timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function replyWithDeduplication(api, settings, text, event, context) {
  const key = messageKey(event, context);
  const now = Date.now();
  pruneCompletedReplies(now);

  if (key) {
    const completed = completedReplies.get(key);
    if (completed && completed.expiresAt > now) {
      return completed.text;
    }
    const inFlight = inFlightReplies.get(key);
    if (inFlight) {
      return inFlight;
    }
  }

  const request = requestDigitalPerson(api, settings, text, event, context);
  if (key) {
    inFlightReplies.set(key, request);
  }
  try {
    const reply = await request;
    if (key) {
      completedReplies.set(key, {
        text: reply,
        expiresAt: Date.now() + settings.dedupeTtlMs,
      });
    }
    return reply;
  } finally {
    if (key) {
      inFlightReplies.delete(key);
    }
  }
}

export default definePluginEntry({
  id: "digital-person-direct-bridge",
  name: "Digital Person Direct WeChat Bridge",
  description:
    "Routes ordinary WeChat messages directly to the Digital Person backend before agent routing.",
  register(api) {
    api.on(
      "inbound_claim",
      async (event, context) => {
        const settings = pluginSettings(api);
        if (incomingChannel(event, context) !== settings.channelId) {
          return { handled: false };
        }
        if (settings.directMessagesOnly && event.isGroup === true) {
          return { handled: false };
        }

        const text = messageText(event);
        if (text?.startsWith("/")) {
          return { handled: false };
        }
        if (!text) {
          return {
            handled: true,
            reply: { text: settings.unsupportedMessage },
          };
        }

        try {
          const reply = await replyWithDeduplication(api, settings, text, event, context);
          api.logger.info?.(
            `[digital-person-direct-bridge] handled channel=${settings.channelId} messageId=${
              event.messageId ?? context.messageId ?? "unknown"
            }`,
          );
          return {
            handled: true,
            reply: { text: reply },
          };
        } catch (error) {
          api.logger.error?.(
            `[digital-person-direct-bridge] backend request failed: ${
              error instanceof Error ? error.message : String(error)
            }`,
          );
          // Fail closed: do not fall through to the OpenClaw agent/session path,
          // because that would reintroduce compaction and could duplicate a request.
          return {
            handled: true,
            reply: { text: settings.backendErrorMessage },
          };
        }
      },
      {
        priority: 1_000,
        timeoutMs: 240_000,
      },
    );
  },
});
