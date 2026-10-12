import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';
import { cpSync, mkdirSync } from 'node:fs';

export default defineConfig({
  plugins: [vue({template:{transformAssetUrls:false}}), {name:'desktop-resources',closeBundle(){
    mkdirSync(resolve('dist/icons'),{recursive:true});
    cpSync(resolve('../management-cli/src/main/resources/cn/dreamingfish/updater/management/cli/web/icons'),resolve('dist/icons'),{recursive:true});
    cpSync(resolve('../player-ui/dist'),resolve('dist/player-preview'),{recursive:true});
  }}],
  build:{rollupOptions:{input:{launcher:resolve('index.html'),workspace:resolve('workspace.html')}}},
  server:{host:'127.0.0.1',port:1421,strictPort:true},
});
