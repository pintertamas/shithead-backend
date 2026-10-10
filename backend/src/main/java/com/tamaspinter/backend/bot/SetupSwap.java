package com.tamaspinter.backend.bot;

import java.util.List;

/** Hand and face-up indexes a bot swaps during setup; the i-th hand card trades places with the i-th face-up card. */
public record SetupSwap(List<Integer> handIndices, List<Integer> faceUpIndices) {

    /** Keeps the dealt cards as they are. */
    public static SetupSwap none() {
        return new SetupSwap(List.of(), List.of());
    }

    public boolean isEmpty() {
        return handIndices.isEmpty();
    }
}
