package com.laishengkai.digitalperson.infrastructure.memory;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Rejects an HTTP response as soon as its body exceeds the configured limit. */
final class LimitedByteArrayBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private final int maximumBytes;

    LimitedByteArrayBodyHandler(int maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(
            HttpResponse.ResponseInfo responseInfo
    ) {
        return new Subscriber(maximumBytes);
    }

    private static final class Subscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long receivedBytes;

        private Subscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = Objects.requireNonNull(value, "subscription cannot be null");
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                ByteBuffer readable = buffer.duplicate();
                int remaining = readable.remaining();
                if (receivedBytes + remaining > maximumBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new Mem0ClientException(
                            "Mem0 response exceeded maxResponseBytes="
                                    + maximumBytes
                    ));
                    return;
                }
                byte[] bytes = new byte[remaining];
                readable.get(bytes);
                output.writeBytes(bytes);
                receivedBytes += remaining;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
