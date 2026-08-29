package com.quant.frtb;

import java.util.List;

/**
 * A trading desk: name plus instrument list (which may be empty — the
 * capital of an empty desk is zero throughout the engine).
 *
 * @param name        desk key (e.g. "desk1")
 * @param display     human-readable desk name
 * @param instruments the desk's positions (immutable list)
 */
public record Desk(String name, String display, List<Instrument> instruments) {

    public Desk {
        instruments = List.copyOf(instruments);
    }
}
