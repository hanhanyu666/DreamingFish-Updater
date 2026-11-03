import { ref, watch, type Ref } from 'vue';
import { emptyHosting, hostingEntryLabel, loadHostingHistory, rememberHosting, restoreHosting, saveHostingHistory,
    type HostingEntry, type HostingMethod, type HostingValues } from './hosting-history.ts';

type Persistence = { load: typeof loadHostingHistory; save: typeof saveHostingHistory };
export function useHostingHistory(scope: Ref<string>, form: HostingValues, method: Ref<HostingMethod>,
    persistence: Persistence = { load: loadHostingHistory, save: saveHostingHistory }) {
    const entries = ref<HostingEntry[]>([]), selected = ref(''), loading = ref(false), busy = ref(false), ready = ref(false), error = ref('');
    let epoch = 0, touched = false;
    function touch() { touched = true; }
    function apply(entry: HostingEntry) {
        Object.assign(form, restoreHosting(entry));
        method.value = entry.method;
        selected.value = entry.id;
    }
    watch(scope, async (owner, _previous, cleanup) => {
        const visit = ++epoch;
        cleanup(() => { ++epoch; });
        entries.value = []; selected.value = ''; error.value = ''; ready.value = false; busy.value = false;
        Object.assign(form, emptyHosting()); method.value = 'export'; touched = false;
        loading.value = Boolean(owner);
        if (!owner) return;
        try {
            const saved = await persistence.load(owner);
            if (visit !== epoch) return;
            entries.value = saved;
            ready.value = true;
            if (!touched && saved[0]) apply(saved[0]);
        } catch {
            if (visit === epoch) error.value = '未能读取本机填写历史，本次仍可正常上传。桌面端请确认已升级本机程序；已有记录不会被覆盖。';
        } finally { if (visit === epoch) loading.value = false; }
    }, { immediate: true, flush: 'sync' });
    function restoreSelected() {
        if (loading.value || busy.value) return;
        const entry = entries.value.find(item => item.id === selected.value);
        if (entry) { apply(entry); touched = true; error.value = ''; }
    }
    async function remember() {
        if (!ready.value || busy.value || !scope.value) return false;
        const visit = epoch, owner = scope.value, selectedMethod = method.value, snapshot = { ...form };
        busy.value = true; error.value = '';
        try {
            const next = rememberHosting(await persistence.load(owner), selectedMethod, snapshot);
            if (visit !== epoch) return false;
            await persistence.save(owner, next);
            if (visit !== epoch) return false;
            entries.value = next; selected.value = next[0].id;
            return true;
        } catch (problem) {
            if (visit === epoch) error.value = '未保存填写历史：' + String(problem instanceof Error ? problem.message : problem);
            return false;
        } finally { if (visit === epoch) busy.value = false; }
    }
    async function forget() {
        if (!ready.value || busy.value || !scope.value || !selected.value) return;
        const visit = epoch, owner = scope.value, removed = selected.value;
        busy.value = true; error.value = '';
        try {
            const next = (await persistence.load(owner)).filter(entry => entry.id !== removed);
            if (visit !== epoch) return;
            await persistence.save(owner, next);
            if (visit !== epoch) return;
            entries.value = next; selected.value = '';
        } catch (problem) {
            if (visit === epoch) error.value = '未删除填写历史：' + String(problem instanceof Error ? problem.message : problem);
        } finally { if (visit === epoch) busy.value = false; }
    }
    return { entries, selected, loading, busy, ready, error, touch, restoreSelected, remember, forget, visit: () => epoch, label: hostingEntryLabel };
}
