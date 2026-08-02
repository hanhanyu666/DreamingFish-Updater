<script setup lang="ts">
import { usePlayerStore } from "../stores/player";
import NewsPage from "./NewsPage.vue";

const store = usePlayerStore();
const VERSION = "0.1.26";
</script>

<template>
  <div class="content-page-layer" :class="{ visible: store.state.page !== 'HOME' }">
    <div
      v-if="store.state.page === 'NEWS'"
      :key="'news-' + store.state.newsRequest.seq"
      class="content-page-host page-reveal"
    >
      <NewsPage />
    </div>
    <div
      v-else-if="store.state.page === 'DREAM_HAVEN'"
      :key="'dreamhaven'"
      class="content-page-host page-reveal"
    >
      <div class="content-page-alignment">
        <div class="content-page">
          <div class="page-eyebrow">NEXT SEASON</div>
          <div class="page-title">灾变之后，梦屿仍在</div>
          <div class="page-lead">一段由全服玩家共同发现、共同判断，也共同书写结局的新故事。</div>
          <div class="page-divider"></div>
          <div class="page-copy">
            守望梦屿是梦鱼服正在精心开发的下一周目，也是一个全新的故事起点。梦屿曾是一片让人慢下来生活、重新开始做梦的温柔之地；如今，污染、失序与未知危机正在改变它。
          </div>
          <div class="page-copy">
            玩家将以幸存者的身份踏入灾变后的大陆，在建造据点、寻找记录、整理线索、推进剧情任务、发展人物关系与多人协作中，一步步推动服务器的故事。探索不只是收集物资，每一段广播、每一次对话和每一份残缺记录，都可能改变大家对灾难的理解。
          </div>
          <div class="page-copy">
            线索不会直接给出唯一答案。你需要与伙伴分享发现，判断记录是否可信，推理灾难真正的成因，并为全服的下一步行动作出选择。错误的结论可能将梦屿推向毁灭，而真正的破局之法，也许就藏在某位玩家的发现之中。
          </div>
          <div class="page-callout">守望梦屿将于今年夏天推出。</div>
        </div>
      </div>
    </div>
    <div
      v-else-if="store.state.page === 'ABOUT'"
      :key="'about'"
      class="content-page-host page-reveal"
    >
      <div class="content-page-alignment">
        <div class="content-page">
          <div class="page-eyebrow">DREAMINGFISH UPDATER</div>
          <div class="page-version">版本 {{ VERSION }}</div>
          <div class="page-title">关于梦鱼服</div>
          <div class="page-divider"></div>
          <div class="page-copy">
            梦鱼服最早成立于 2021 年 7 月 10 日。成立之初，我们就希望为玩家带来一个公平、自由、富有探索感的模组生存体验，让大家能在同一个世界里建设、冒险、交流，并留下属于自己的故事。
          </div>
          <div class="page-copy">
            在最初的基岩版时期，服务器尝试了大量优质模组内容，也积累了一批活跃而稳定的玩家。梦鱼服始终坚持公益运营，不售卖强度，不以付费优势破坏玩家体验；服务器的更新方向，也主要来自腐竹筛选与玩家推荐。
          </div>
          <div class="page-copy">
            2022 年 11 月，服务器因腐竹学业暂时停服。2024 年高考结束后，梦鱼服重新启动，并从基岩版模组服转向 Java 版模组服，以追求更稳定、更自由、更适合长期开发的体验。
          </div>
          <div class="page-copy">
            现在，我们正围绕服务器自研模组、设计玩法系统，并持续打磨属于梦鱼服自己的内容。我们想创造的不只是一个"装了很多模组"的服务器，而是一种更完整、更有参与感，也更值得玩家长期投入的新体验。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
