package com.rook.hook;

import java.util.List;

/** 条件组合 —— all_of / any_of */
public sealed interface HookCondition {
    boolean evaluate(HookContext ctx);

    record AllOf(List<HookMatcher> matchers) implements HookCondition {
        @Override public boolean evaluate(HookContext ctx) {
            return matchers.stream().allMatch(m -> m.matches(ctx.getField(m.field())));
        }
    }
    record AnyOf(List<HookMatcher> matchers) implements HookCondition {
        @Override public boolean evaluate(HookContext ctx) {
            return matchers.stream().anyMatch(m -> m.matches(ctx.getField(m.field())));
        }
    }
}
