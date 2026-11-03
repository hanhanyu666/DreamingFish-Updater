import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';
import { cpSync } from 'node:fs';

export default defineConfig({
  plugins: [vue({template:{transformAssetUrls:false}}), {name:'desktop-resources',closeBundle(){
    cpSync(resolve('../player-ui/dist'),resolve('dist/player-preview'),{recursive:true});
  }}],
  build:{rollupOptions:{input:{launcher:resolve('index.html'),workspace:resolve('workspace.html')}}},
  // The showcase rules and docs are shared with player-ui, one level up.
  server:{host:'127.0.0.1',port:1421,strictPort:true,fs:{allow:['..']}},
});
