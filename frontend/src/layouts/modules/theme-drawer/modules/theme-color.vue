<script setup lang="ts">
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import SettingItem from '../components/setting-item.vue';

defineOptions({
  name: 'ThemeColor'
});

const themeStore = useThemeStore();

function handleUpdateColor(color: string, key: App.Theme.ThemeColorKey) {
  themeStore.updateThemeColors(key, color);
}

const swatches: string[] = ['#1a1a19', '#34322d', '#5e5e5b', '#858481', '#d6d5d2', '#dfdfdd', '#efefed', '#f8f8f7'];
</script>

<template>
  <NDivider>{{ $t('theme.themeColor.title') }}</NDivider>
  <div class="flex-col-stretch gap-12px">
    <NTooltip placement="top-start">
      <template #trigger>
        <SettingItem key="recommend-color" :label="$t('theme.recommendColor')">
          <NSwitch v-model:value="themeStore.recommendColor" />
        </SettingItem>
      </template>
      <p>
        <span class="pr-12px">{{ $t('theme.recommendColorDesc') }}</span>
        <br />
        <NButton
          text
          tag="a"
          href="https://uicolors.app/create"
          target="_blank"
          rel="noopener noreferrer"
          class="text-gray"
        >
          https://uicolors.app/create
        </NButton>
      </p>
    </NTooltip>
    <SettingItem v-for="(_, key) in themeStore.themeColors" :key="key" :label="$t(`theme.themeColor.${key}`)">
      <template v-if="key === 'info'" #suffix>
        <NCheckbox v-model:checked="themeStore.isInfoFollowPrimary">
          {{ $t('theme.themeColor.followPrimary') }}
        </NCheckbox>
      </template>
      <NColorPicker
        class="w-90px"
        :value="themeStore.themeColors[key]"
        :disabled="key === 'info' && themeStore.isInfoFollowPrimary"
        :show-alpha="false"
        :swatches="swatches"
        @update:value="handleUpdateColor($event, key)"
      />
    </SettingItem>
  </div>
</template>

<style scoped></style>
