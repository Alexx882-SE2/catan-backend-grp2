package com.group2.catanbackend.dto.game;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class RollDiceDto extends GameMoveDto{
    @Getter
    private int diceRoll;
}

