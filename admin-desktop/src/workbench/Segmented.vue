<script setup lang="ts">
import { ref } from 'vue';
import { useIndicator } from './indicator';
const props=defineProps<{items:{id:string;label:string;count?:number|string}[];modelValue:string;label:string}>();
const emit=defineEmits<{'update:modelValue':[value:string]}>();
const root=ref<HTMLElement|null>(null);
const indicator=useIndicator(root,()=>[props.modelValue,props.items.map(i=>i.label+(i.count??'')).join()]);
function step(event:KeyboardEvent){const i=props.items.findIndex(item=>item.id===props.modelValue),next=props.items[(i+(event.key==='ArrowRight'?1:-1)+props.items.length)%props.items.length];emit('update:modelValue',next.id);void Promise.resolve().then(()=>root.value?.querySelector<HTMLElement>('[data-active="true"]')?.focus());}
</script>
<template><div ref="root" class="segmented has-indicator" role="tablist" :aria-label="label" @keydown.left.prevent="step" @keydown.right.prevent="step"><span class="segment-indicator" :style="indicator" aria-hidden="true"/><button v-for="item in items" :key="item.id" type="button" role="tab" :class="{active:modelValue===item.id}" :data-active="modelValue===item.id" :aria-selected="modelValue===item.id" :tabindex="modelValue===item.id?0:-1" @click="emit('update:modelValue',item.id)">{{item.label}}<small v-if="item.count!==undefined">{{item.count}}</small></button></div></template>
