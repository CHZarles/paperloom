<script setup lang="ts">
import { defineAsyncComponent } from 'vue';
import type { NScrollbar } from 'naive-ui';

const ChatMessage = defineAsyncComponent(() => import('../chat/modules/chat-message.vue'));
const MarkdownProvider = defineAsyncComponent(() =>
  import('@/vendor/vue-markdown-shiki').then(module => module.VueMarkdownItProvider)
);

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);
const userId = ref<number | null>(null);

const params = computed(() => {
  const query: {
    userid?: number;
    start_date?: string;
    end_date?: string;
  } = {};

  if (userId.value !== null) {
    query.userid = userId.value;
  }

  if (range.value) {
    query.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    query.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }

  return query;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  }
  loading.value = false;
}
</script>

<template>
  <div class="admin-console-page chat-history-page flex-col-stretch gap-16px overflow-hidden">
    <NCard
      title="Chat History / 会话历史"
      :bordered="false"
      size="small"
      class="admin-console-card chat-history-card card-wrapper"
      content-class="chat-history-card__content"
    >
      <template #header-extra>
        <div class="chat-history-filter">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="用户">
              <TheSelect
                v-model:value="userId"
                url="admin/users/list"
                :params="{ page: 1, size: 999 }"
                key-field="content"
                value-field="userId"
                label-field="username"
                class="clear w-200px!"
                placeholder="全部用户"
              />
            </NFormItem>
            <NFormItem label="时间">
              <NDatePicker v-model:value="range" type="daterange" class="clear" clearable />
            </NFormItem>
          </NForm>
        </div>
      </template>

      <NScrollbar ref="scrollbarRef" class="chat-history-scroll">
        <NSpin :show="loading" class="h-full">
          <component :is="list.length ? MarkdownProvider : 'div'">
            <div class="chat-history-list">
              <ChatMessage
                v-for="(item, index) in list"
                :key="
                  item.conversationRecordId
                    ? `${item.conversationRecordId}:${item.role}`
                    : `${item.timestamp}:${item.role}:${index}`
                "
                :msg="item"
              />
            </div>
          </component>
          <NEmpty v-if="!list.length" description="暂无数据" class="mt-60" />
        </NSpin>
      </NScrollbar>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.chat-history-page {
  min-height: 0;
}

.chat-history-card {
  min-height: 0;
  flex: 1 1 auto;
}

.chat-history-card :deep(.chat-history-card__content) {
  display: flex;
  min-height: 0;
  flex-direction: column;
  padding: 0;
}

.chat-history-filter {
  min-width: 0;
}

.chat-history-filter :deep(.n-form) {
  gap: 8px 12px;
}

.chat-history-filter :deep(.n-form-item) {
  margin-right: 0;
}

.chat-history-filter :deep(.n-form-item-label) {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.chat-history-scroll {
  min-height: 0;
  flex: 1 1 auto;
  padding: 16px 20px;
}

.chat-history-list {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
}

@media (max-width: 720px) {
  .chat-history-card :deep(.n-card-header) {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .chat-history-card :deep(.n-card-header__main),
  .chat-history-card :deep(.n-card-header__extra),
  .chat-history-filter,
  .chat-history-filter :deep(.n-form),
  .chat-history-filter :deep(.n-form-item) {
    width: 100%;
  }

  .chat-history-filter :deep(.n-date-picker),
  .chat-history-filter :deep(.n-base-selection) {
    width: 100%;
  }
}
</style>
