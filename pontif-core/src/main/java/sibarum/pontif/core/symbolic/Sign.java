package sibarum.pontif.core.symbolic;

public enum Sign {
    BOTTOM, ZERO, POSITIVE, NEGATIVE, NON_NEGATIVE, NON_POSITIVE, TOP;

    public Sign add(Sign other) {
        if (this == BOTTOM || other == BOTTOM) return BOTTOM;
        if (this == ZERO) return other;
        if (other == ZERO) return this;
        return switch (this) {
            case POSITIVE -> switch (other) {
                case POSITIVE, NON_NEGATIVE -> POSITIVE;
                default -> TOP;
            };
            case NEGATIVE -> switch (other) {
                case NEGATIVE, NON_POSITIVE -> NEGATIVE;
                default -> TOP;
            };
            case NON_NEGATIVE -> switch (other) {
                case NON_NEGATIVE -> NON_NEGATIVE;
                case POSITIVE -> POSITIVE;
                default -> TOP;
            };
            case NON_POSITIVE -> switch (other) {
                case NON_POSITIVE -> NON_POSITIVE;
                case NEGATIVE -> NEGATIVE;
                default -> TOP;
            };
            default -> TOP;
        };
    }

    public Sign multiply(Sign other) {
        if (this == BOTTOM || other == BOTTOM) return BOTTOM;
        if (this == ZERO || other == ZERO) return ZERO;
        return switch (this) {
            case POSITIVE -> other;
            case NEGATIVE -> other.negate();
            case NON_NEGATIVE -> switch (other) {
                case POSITIVE, NON_NEGATIVE -> NON_NEGATIVE;
                case NEGATIVE, NON_POSITIVE -> NON_POSITIVE;
                default -> TOP;
            };
            case NON_POSITIVE -> switch (other) {
                case POSITIVE, NON_NEGATIVE -> NON_POSITIVE;
                case NEGATIVE, NON_POSITIVE -> NON_NEGATIVE;
                default -> TOP;
            };
            default -> TOP;
        };
    }

    public Sign negate() {
        return switch (this) {
            case POSITIVE -> NEGATIVE;
            case NEGATIVE -> POSITIVE;
            case NON_NEGATIVE -> NON_POSITIVE;
            case NON_POSITIVE -> NON_NEGATIVE;
            case ZERO, TOP, BOTTOM -> this;
        };
    }

    public Sign meet(Sign other) {
        if (this == other) return this;
        if (this == BOTTOM || other == BOTTOM) return BOTTOM;
        if (this == TOP) return other;
        if (other == TOP) return this;
        return switch (this) {
            case POSITIVE -> switch (other) {
                case NON_NEGATIVE -> POSITIVE;
                case NEGATIVE, NON_POSITIVE, ZERO -> BOTTOM;
                default -> this;
            };
            case NEGATIVE -> switch (other) {
                case NON_POSITIVE -> NEGATIVE;
                case POSITIVE, NON_NEGATIVE, ZERO -> BOTTOM;
                default -> this;
            };
            case ZERO -> switch (other) {
                case NON_NEGATIVE, NON_POSITIVE -> ZERO;
                case POSITIVE, NEGATIVE -> BOTTOM;
                default -> this;
            };
            case NON_NEGATIVE -> switch (other) {
                case POSITIVE -> POSITIVE;
                case ZERO, NON_POSITIVE -> ZERO;
                case NEGATIVE -> BOTTOM;
                default -> this;
            };
            case NON_POSITIVE -> switch (other) {
                case NEGATIVE -> NEGATIVE;
                case ZERO, NON_NEGATIVE -> ZERO;
                case POSITIVE -> BOTTOM;
                default -> this;
            };
            default -> BOTTOM;
        };
    }
}
