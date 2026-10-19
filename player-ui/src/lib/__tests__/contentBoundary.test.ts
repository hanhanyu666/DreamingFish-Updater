import {describe,it,expect} from 'vitest';
import {renderMarkdown,resolveImageSource,safeWebUri} from '../markdown';
import {acceptedPreviewMessage} from '../previewBoundary';

describe('content and native boundary',()=>{
 it('removes raw HTML, event attributes and dangerous URL schemes',()=>{
  const html=renderMarkdown('<img src=x onerror="alert(1)">\n\n[bad](javascript:alert%281%29)\n\n![x](data:text/html,evil)');
  expect(html).not.toContain('onerror');expect(html).not.toContain('href="javascript:');expect(html).not.toContain('src="data:');
  for(const value of ['javascript:alert(1)','file:///secret','data:image/svg+xml,bad','//evil.test/a','../a.png','/%2e%2e/a.png','a\\b.png','C:/a.png'])expect(resolveImageSource(value),value).toBeNull();
  expect(safeWebUri('https://user:password@example.test/')).toBeNull();
 });
 it('preserves normal text, external images and supported local assets',()=>{
  expect(renderMarkdown('普通文字 **中文**')).toContain('<strong');
  expect(resolveImageSource('https://example.test/a.png')).toBe('https://example.test/a.png');
  expect(resolveImageSource('http://example.test/a.png')).toBe('http://example.test/a.png');
  expect(resolveImageSource('images/中文.png')).toBe('/images/中文.png');
  expect(renderMarkdown('[链接](https://example.test/)')).toContain('rel="noopener noreferrer"');
 });
 it('accepts preview data only from its same-origin parent frame',()=>{
  const parent={} as Window,self={} as Window,origin='https://admin.example.test';
  const event={origin,source:parent,data:{type:'dfs-admin-preview',branding:{productName:'Preview'}}} as MessageEvent;
  expect(acceptedPreviewMessage(event,origin,parent,self,true)).toBe(true);
  expect(acceptedPreviewMessage({...event,origin:'https://evil.test'} as MessageEvent,origin,parent,self,true)).toBe(false);
  expect(acceptedPreviewMessage({...event,source:self} as MessageEvent,origin,parent,self,true)).toBe(false);
  expect(acceptedPreviewMessage(event,origin,parent,parent,true)).toBe(false);
  expect(acceptedPreviewMessage(event,origin,parent,self,false)).toBe(false);
 });
});
