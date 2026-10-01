// Terser 5.48.0's `booleans` pass miscompiles Kotlin's `x?.let { predicate(it) } ?: true`.
//
// Kotlin/JS lowers it to `(x == null ? null : predicate(x)) ?? true`, and when that value is used
// as a condition, `booleans` treats the left operand of `??` as a boolean context and rewrites the
// `null` branch to `false`: `(x != null && predicate(x)) ?? 1`. `false ?? 1` is `false`, so a
// missing value that should read as "no constraint" reads as "rejected" -- in the production
// bundle only, which is why the Karma tests (development build) never saw it.
//
// It shipped twice (2026-09-30): `HeroCampaign.activeFormationId` refused every formation the
// roster had no record of yet -- no hero could emerge in a new formation, its combats took the
// legacy leader path instead, and it could never be offered an attachment -- and
// `CampaignCondition.matchesCounts` failed every condition with no `minSuccesses`.
//
// Switching the one pass off costs a few bytes and fixes every present and future instance of an
// idiom Kotlin writes constantly. Remove only after a Terser upgrade passes
// `scripts/verify/terser-nullish-probe.mjs`.
if (config.mode === "production") {
    const TerserPlugin = require("terser-webpack-plugin");
    config.optimization = config.optimization || {};
    config.optimization.minimizer = [
        new TerserPlugin({
            terserOptions: {
                compress: { booleans: false },
            },
        }),
    ];
}
