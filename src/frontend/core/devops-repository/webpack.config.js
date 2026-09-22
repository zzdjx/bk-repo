const path = require('path')
const HtmlWebpackPlugin = require('html-webpack-plugin')
const CopyWebpackPlugin = require('copy-webpack-plugin')
const webpackBaseConfig = require('../../webpack.base')
const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin')

const frontendNodeModules = path.join(__dirname, '../../node_modules')

module.exports = (env, argv) => {
    const isProd = argv.mode === 'production'
    const envDist = env && env.dist ? env.dist : 'frontend'
    const dist = path.join(__dirname, `../../${envDist}/ui`)
    const config = webpackBaseConfig({
        env,
        argv,
        entry: {
            repository: './src/main.js'
        },
        publicPath: '',
        dist: '/ui',
        port: 8086
    })
    config.plugins.pop()
    config.plugins = [
        ...config.plugins,
        new HtmlWebpackPlugin({
            filename: isProd ? `${dist}/frontend_ui_index.html` : `${dist}/index.html`,
            template: 'index.html',
            inject: true,
            title: '制品库 | 腾讯蓝鲸智云'
        }),
        new CopyWebpackPlugin({
            patterns: [
                { from: path.join(__dirname, './static'), to: dist },
                { from: path.join(__dirname, '../../../../versionLogs'), to: `${dist}/versionLogs` },
                { from: path.join(__dirname, './public'), to: dist },
                { from: path.join(frontendNodeModules, '@babel/standalone/babel.min.js'), to: `${dist}/libs/babel.min.js` },
                { from: path.join(frontendNodeModules, 'mermaid/dist/mermaid.min.js'), to: `${dist}/libs/mermaid.min.js` },
                { from: path.join(frontendNodeModules, 'katex/dist/katex.min.js'), to: `${dist}/libs/katex/katex.min.js` },
                { from: path.join(frontendNodeModules, 'katex/dist/katex.min.css'), to: `${dist}/libs/katex/katex.min.css` },
                { from: path.join(frontendNodeModules, 'katex/dist/fonts'), to: `${dist}/libs/katex/fonts` },
                { from: path.join(frontendNodeModules, '@plantuml/core/plantuml.js'), to: `${dist}/libs/plantuml/plantuml.js` },
                { from: path.join(frontendNodeModules, '@plantuml/core/viz-global.js'), to: `${dist}/libs/plantuml/viz-global.js` },
                { from: path.join(frontendNodeModules, '@plantuml/core/emoji.js'), to: `${dist}/libs/plantuml/emoji.js` },
                { from: path.join(frontendNodeModules, '@plantuml/core/openiconic.js'), to: `${dist}/libs/plantuml/openiconic.js` },
                { from: path.join(frontendNodeModules, '@google/model-viewer/dist/model-viewer.min.js'), to: `${dist}/libs/model-viewer/model-viewer.min.js` },
                { from: path.join(frontendNodeModules, 'three/examples/jsm/libs/draco/gltf'), to: `${dist}/libs/model-viewer/draco` },
                { from: path.join(frontendNodeModules, 'meshoptimizer/meshopt_decoder.js'), to: `${dist}/libs/model-viewer/meshopt_decoder.js` }
            ]
        }),
        new MonacoWebpackPlugin()
    ]

    config.devServer.historyApiFallback = {
        rewrites: [
            { from: /^\/ui/, to: '/ui/index.html' }
        ]
    }
    return config
}
