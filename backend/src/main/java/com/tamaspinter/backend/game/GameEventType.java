package com.tamaspinter.backend.game;

/** Kinds of entries in the game activity feed. */
public enum GameEventType {
    PLAYED,
    PLAYED_AGAIN,
    REVERSED,
    BURNED,
    PICKED_UP,
    FAILED_FLIP,
    FAILED_PLAY,
    READY,
    OUT,
    FINISHED
}
