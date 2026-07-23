package com.laishengkai.digitalperson.activity;

/** One provider-neutral event lifecycle command proposed by the activity model. */
public sealed interface ActivityLifecycleCommand
        permits StartActivityCommand, FinishActivityCommand {

    ActivityLifecycleAction action();
}
