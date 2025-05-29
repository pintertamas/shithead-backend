package com.tamaspinter.backend.model.websocket;

import com.tamaspinter.backend.model.Card;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class PlayMessage {
    private String sessionId;
    private List<Card> cards;
}