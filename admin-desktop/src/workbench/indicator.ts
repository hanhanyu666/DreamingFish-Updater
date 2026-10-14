import {nextTick,onMounted,onUnmounted,ref,watch,type Ref,type WatchSource} from 'vue';

// Slides one highlight under the active item of a capsule control instead of repainting each item.
export function useIndicator(container:Ref<HTMLElement|null>,source:WatchSource,vertical=false){
 const style=ref<Record<string,string>>({opacity:'0'});
 let placed=false;
 function place(){
  const item=container.value?.querySelector<HTMLElement>(':scope > [data-active="true"]');
  if(!item){style.value={opacity:'0'};placed=false;return;}
  style.value={...(vertical?{transform:`translateY(${item.offsetTop}px)`,height:`${item.offsetHeight}px`,width:`${item.offsetWidth}px`,left:`${item.offsetLeft}px`}:{transform:`translateX(${item.offsetLeft}px)`,width:`${item.offsetWidth}px`,height:`${item.offsetHeight}px`,top:`${item.offsetTop}px`}),...(placed?{}:{transition:'none'})};
  placed=true;
 }
 const observer=new ResizeObserver(()=>place());
 // The container can appear after mount (the rail renders only once signed in), and its width can
 // animate; follow every size change so the highlight never keeps a stale width.
 watch(container,(element,previous)=>{if(previous)observer.unobserve(previous);if(element){observer.observe(element);place();}},{flush:'post',immediate:true});
 onMounted(()=>{place();void document.fonts?.ready.then(place);});
 onUnmounted(()=>observer.disconnect());
 watch(source,()=>void nextTick(place));
 return style;
}
