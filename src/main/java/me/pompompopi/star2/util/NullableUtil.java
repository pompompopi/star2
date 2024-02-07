package me.pompompopi.star2.util;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NullableUtil {
    private NullableUtil() {

    }

    public static <T> Optional<T> mapToPossiblyEmpty(final T value, final Predicate<T> nullPredicate) {
        if (nullPredicate.test(value))
            return Optional.empty();
        return Optional.of(value);
    }

    public static <T, V> @Nullable V mapFromPossiblyNull(final @Nullable T value, final Function<T, V> mapper) {
        if (value == null)
            return null;
        return mapper.apply(value);
    }
}
