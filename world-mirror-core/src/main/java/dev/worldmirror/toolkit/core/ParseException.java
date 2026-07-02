package dev.worldmirror.toolkit.core;

/** Thrown when a replay packet, schema, or Minecraft binary structure cannot be parsed. */
public final class ParseException extends ToolkitException {
    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
