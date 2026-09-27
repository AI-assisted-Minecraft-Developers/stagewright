package net.magicterra.stagewright.cli;

/**
 * The run cannot start here, for a reason in the environment rather than in the command line: exit 3
 * with the message, and without the usage text an argument error gets.
 */
final class EnvFailure extends RuntimeException {

    EnvFailure(String message) {
        super(message);
    }
}
