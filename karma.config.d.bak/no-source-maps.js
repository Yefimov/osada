// The Kotlin/JS-generated source-map-loader rule cannot be resolved from the
// project root in this Windows/yarn-workspace setup. Disable webpack source maps
// and the loader so Karma can bundle and run the test suite.
const webpack = require('webpack');
config.webpack = config.webpack || {};
config.webpack.devtool = false;
config.webpack.module = config.webpack.module || {};
config.webpack.module.rules = (config.webpack.module.rules || [])
    .filter(function(rule) {
        var uses = Array.isArray(rule.use) ? rule.use : [rule.use];
        return !uses.some(function(u) {
            return typeof u === 'string' && u.indexOf('source-map-loader') !== -1;
        });
    });
config.webpack.plugins = (config.webpack.plugins || [])
    .filter(function(plugin) {
        return !(plugin instanceof webpack.SourceMapDevToolPlugin);
    });
