<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { mixColor } from '@sa/color';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');

const bgColor = computed(() => {
  const ratio = themeStore.darkMode ? 0.9 : 0;

  return mixColor('#eeeae1', '#000', ratio);
});
</script>

<template>
  <div class="login-page relative size-full flex-center" :style="{ backgroundColor: bgColor }">
    <NCard :bordered="false" class="relative z-4 w-auto card-wrapper">
      <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'">
        <header class="login-header">
          <div class="login-brand">
            <div class="login-brand-mark">
              <SystemLogo class="text-58px lt-sm:text-46px" />
            </div>
            <div class="min-w-0">
              <h3 class="login-brand-title">
                <span>{{ $t('system.title') }}</span>
                <span v-if="isRegisterModule" class="login-brand-mode">{{ $t(activeModule.label) }}</span>
              </h3>
              <div class="login-brand-subtitle">paper · claims · cited evidence</div>
            </div>
          </div>
          <div class="login-tools">
            <ThemeSchemaSwitch
              :theme-schema="themeStore.themeScheme"
              :show-tooltip="false"
              class="text-20px lt-sm:text-18px"
              @switch="themeStore.toggleThemeScheme"
            />
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
        </header>
        <main class="pt-24px">
          <div v-if="!isRegisterModule" class="login-module-heading">
            <span>[auth]</span>
            {{ $t(activeModule.label) }}
          </div>
          <div class="pt-24px">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </div>
        </main>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.login-page {
  background:
    linear-gradient(90deg, rgba(38, 54, 74, 0.035) 1px, transparent 1px),
    linear-gradient(180deg, rgba(32, 36, 42, 0.035) 1px, transparent 1px), #eeeae1;
  background-size: 24px 24px;
}

:deep(.card-wrapper) {
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
  box-shadow: 8px 8px 0 rgba(201, 193, 178, 0.7);
}

.login-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.login-brand {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}

.login-brand-mark {
  display: flex;
  height: 70px;
  width: 70px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #e2dccc;
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.68);
}

.login-brand-title {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
  margin: 0;
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.05;
}

.login-brand-mode {
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  font-weight: 700;
}

.login-brand-subtitle {
  margin-top: 7px;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.login-tools {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #26364a;
}

.login-module-heading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #c9c1b2;
  color: #26364a;
  padding-bottom: 6px;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 18px;
  font-weight: 700;
}

.login-module-heading span {
  color: #9a6428;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.login-panel {
  width: 400px;
}

.login-panel--register {
  width: min(860px, calc(100vw - 72px));
}

@media (max-width: 640px) {
  .login-panel,
  .login-panel--register {
    width: 300px;
  }

  .login-header {
    gap: 12px;
  }

  .login-brand-title {
    flex-direction: column;
    align-items: flex-start;
    font-size: 24px;
  }
}
</style>
