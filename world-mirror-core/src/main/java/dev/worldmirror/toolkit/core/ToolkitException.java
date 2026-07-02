package dev.worldmirror.toolkit.core;

/**
 * Base unchecked exception for recoverable toolkit failures.
 *
 * <p>The toolkit is intentionally strict around binary formats. A malformed replay event, an
 * unsupported schema, or a truncated NBT payload should fail loudly instead of silently producing a
 * corrupt world mirror.</p>
 */
public class ToolkitException extends RuntimeException {
    public ToolkitException(String message) {
        super(message);
    }

    public ToolkitException(String message, Throwable cause) {
        super(message, cause);
    }
}
