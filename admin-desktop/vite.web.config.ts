import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import {resolve} from 'node:path';
import {renameSync} from 'node:fs';

// The web UI uses the exact workbench source shipped in the desktop app.
export default defineConfig({
 base:'/workbench/',
 plugins:[vue({template:{transformAssetUrls:false}}),{name:'web-entry',closeBundle(){renameSync(resolve('web-dist/workspace.html'),resolve('web-dist/index.html'));}}],
 build:{outDir:'web-dist',emptyOutDir:true,rollupOptions:{input:resolve('workspace.html')}},
});
