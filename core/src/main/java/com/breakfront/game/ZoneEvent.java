package com.breakfront.game;

/** 据点事件。 */
public record ZoneEvent(ZoneEventType type, String zoneId, Side capturedBy) {
}
