package dev.shrimpscript.tumble.physics;

/** A position-level constraint solved once per substep. */
public interface Constraint {

    void solvePosition(double h);
}
