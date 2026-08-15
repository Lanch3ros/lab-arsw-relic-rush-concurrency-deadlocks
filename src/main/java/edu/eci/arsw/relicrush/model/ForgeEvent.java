package edu.eci.arsw.relicrush.model;

public record ForgeEvent(
        int round,
        String adventurer,
        String firstStation,
        String secondStation,
        int scoreAfterCraft) {
}
