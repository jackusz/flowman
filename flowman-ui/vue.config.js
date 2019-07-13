module.exports = {
  outputDir: 'target/classes/META-INF/resources/webjars/flowman-ui',
  devServer: {
    port: 8088,
    proxy: {
      '^/api': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
}
