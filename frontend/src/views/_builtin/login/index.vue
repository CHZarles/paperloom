<script setup lang="ts">
import type { Component } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
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

interface Poster {
  title: string;
  authors: string;
  year: string;
  tone: number;
}

const posters: Poster[] = [
  { title: 'Attention Is All You Need', authors: 'Vaswani et al.', year: '2017', tone: 4 },
  { title: 'A Mathematical Theory of Communication', authors: 'Shannon', year: '1948', tone: 5 },
  { title: 'Deep Residual Learning', authors: 'He et al.', year: '2015', tone: 2 },
  { title: 'The Market for Lemons', authors: 'Akerlof', year: '1970', tone: 3 },
  { title: 'Highly Accurate Protein Structure Prediction', authors: 'Jumper et al.', year: '2021', tone: 2 },
  { title: 'BERT', authors: 'Devlin et al.', year: '2018', tone: 0 },
  { title: 'On the Electrodynamics of Moving Bodies', authors: 'Einstein', year: '1905', tone: 4 },
  { title: 'U-Net', authors: 'Ronneberger et al.', year: '2015', tone: 1 },
  { title: 'Generative Adversarial Nets', authors: 'Goodfellow et al.', year: '2014', tone: 2 },
  { title: 'The Nature of the Firm', authors: 'Coase', year: '1937', tone: 1 },
  { title: 'Batch Normalization', authors: 'Ioffe & Szegedy', year: '2015', tone: 0 },
  { title: 'Sequence to Sequence Learning', authors: 'Sutskever et al.', year: '2014', tone: 3 },
  { title: 'Dropout', authors: 'Srivastava et al.', year: '2014', tone: 1 },
  { title: 'Adam', authors: 'Kingma & Ba', year: '2014', tone: 0 },
  { title: 'ImageNet Classification with Deep CNNs', authors: 'Krizhevsky et al.', year: '2012', tone: 3 },
  { title: 'Efficient Estimation of Word Representations', authors: 'Mikolov et al.', year: '2013', tone: 2 },
  { title: 'Long Short-Term Memory', authors: 'Hochreiter & Schmidhuber', year: '1997', tone: 1 },
  { title: 'Deep Learning', authors: 'LeCun, Bengio & Hinton', year: '2015', tone: 3 },
  { title: 'Generative Pre-trained Transformer', authors: 'Radford et al.', year: '2018', tone: 5 },
  { title: 'The Unreasonable Effectiveness of Data', authors: 'Halevy et al.', year: '2009', tone: 2 }
];

const posterColumns = computed(() => [0, 1, 2, 3].map(column => posters.filter((_, index) => index % 4 === column)));
</script>

<template>
  <div class="landing">
    <section class="landing__story">
      <div class="wall" aria-hidden="true">
        <div
          v-for="(column, columnIndex) in posterColumns"
          :key="columnIndex"
          class="wall__col"
          :class="columnIndex % 2 === 0 ? 'wall__col--up' : 'wall__col--down'"
        >
          <div class="wall__track">
            <article v-for="poster in column" :key="poster.title" class="poster" :class="`poster--tone-${poster.tone}`">
              <span class="poster__year">{{ poster.year }}</span>
              <span class="poster__title">{{ poster.title }}</span>
              <span class="poster__meta">{{ poster.authors }}</span>
              <span class="poster__abstract" />
              <span class="poster__cols">
                <i />
                <i />
              </span>
              <span class="poster__page">1</span>
            </article>
            <article
              v-for="poster in column"
              :key="`${poster.title}-clone`"
              class="poster"
              :class="`poster--tone-${poster.tone}`"
            >
              <span class="poster__year">{{ poster.year }}</span>
              <span class="poster__title">{{ poster.title }}</span>
              <span class="poster__meta">{{ poster.authors }}</span>
              <span class="poster__abstract" />
              <span class="poster__cols">
                <i />
                <i />
              </span>
              <span class="poster__page">1</span>
            </article>
          </div>
        </div>
      </div>

      <div class="veil veil--top"></div>
      <div class="veil veil--bottom"></div>

      <div class="story__layer">
        <div class="story__lockup">
          <SystemLogo class="story__mark" />
          <span class="story__wordmark">{{ $t('system.title') }}</span>
        </div>

        <div class="story__copy">
          <h1 class="story__title">{{ $t('page.landing.headline') }}</h1>
          <p class="story__lede">{{ $t('page.landing.lede') }}</p>
        </div>
      </div>
    </section>

    <aside class="auth-pane">
      <div class="auth-card">
        <header class="auth-card__head">
          <h2 class="auth-card__title">{{ $t(activeModule.label) }}</h2>
          <p class="auth-card__hint">{{ $t('page.landing.authHint') }}</p>
        </header>

        <div class="auth-card__body">
          <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
            <component :is="activeModule.component" />
          </Transition>
        </div>
      </div>

      <div class="auth-pane__locale">
        <LangSwitch
          v-if="themeStore.header.multilingual.visible"
          :lang="appStore.locale"
          :lang-options="appStore.localeOptions"
          :show-tooltip="false"
          @change-lang="appStore.changeLocale"
        />
      </div>
    </aside>
  </div>
</template>

<style scoped>
.landing {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 480px);
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background-color: #fafaf7;
  color: #17181a;
}

/* ---- Left: the wall of papers ---------------------------------------- */
.landing__story {
  position: relative;
  overflow: hidden;
}

.story__layer {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  height: 100%;
  padding: clamp(32px, 4vw, 64px) 0 clamp(28px, 3.4vw, 52px) clamp(28px, 5vw, 80px);
}

.veil {
  position: absolute;
  right: 0;
  left: 0;
  z-index: 1;
  pointer-events: none;
}

.veil--top {
  top: 0;
  height: 210px;
  background: linear-gradient(180deg, #fafaf7 0%, #fafaf7 40%, rgba(250, 250, 247, 0) 100%);
}

.veil--bottom {
  bottom: 0;
  height: 46%;
  background: linear-gradient(180deg, rgba(250, 250, 247, 0) 0%, #fafaf7 58%);
}

.story__lockup {
  display: flex;
  align-items: center;
  gap: 9px;
}

.story__mark {
  flex: 0 0 auto;
  color: #17181a;
  font-size: 22px;
}

.story__wordmark {
  color: #17181a;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.005em;
}

.story__copy {
  max-width: 620px;
  padding-right: clamp(28px, 5vw, 80px);
}

.story__title {
  margin: 0;
  color: #17181a;
  font-size: clamp(34px, 3.6vw, 48px);
  font-weight: 600;
  letter-spacing: -0.035em;
  line-height: 1.08;
  text-wrap: balance;
  animation: rise 520ms cubic-bezier(0.16, 1, 0.3, 1) both;
}

.story__lede {
  max-width: 26em;
  margin: 16px 0 0;
  color: #6e756f;
  font-size: 14.5px;
  line-height: 1.72;
  animation: rise 520ms cubic-bezier(0.16, 1, 0.3, 1) 70ms both;
}

/* ---- The collage ------------------------------------------------------ */
.wall {
  position: absolute;
  inset: 0;
  z-index: 0;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  overflow: hidden;
  animation: rise 700ms cubic-bezier(0.16, 1, 0.3, 1) 150ms both;
}

.wall__col {
  min-width: 0;
}

.wall__track {
  display: flex;
  flex-direction: column;
  gap: 14px;
  animation: wall-up 46s linear infinite;
}

.wall__col--down .wall__track {
  animation-name: wall-down;
  animation-duration: 54s;
}

.poster {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  aspect-ratio: 3 / 4;
  border: 1px solid rgba(23, 24, 26, 0.1);
  border-radius: 3px;
  box-shadow: 0 2px 5px rgba(23, 24, 26, 0.07);
  padding: 11px 12px 9px;
}

.poster--tone-0 {
  background-color: #ece5d3;
}

.poster--tone-1 {
  background-color: #dfe6d6;
}

.poster--tone-2 {
  background-color: #ecdfd2;
}

.poster--tone-3 {
  background-color: #d8e0e5;
}

.poster--tone-4 {
  background-color: #262b2d;
}

.poster--tone-5 {
  background-color: #39423e;
}

.poster--tone-4 .poster__title,
.poster--tone-5 .poster__title {
  color: #f2f1ea;
}

.poster--tone-4 .poster__meta,
.poster--tone-5 .poster__meta {
  color: #b6bcb7;
}

.poster--tone-4 .poster__year,
.poster--tone-5 .poster__year,
.poster--tone-4 .poster__page,
.poster--tone-5 .poster__page {
  color: #f2f1ea;
}

.poster__year {
  border-bottom: 1px solid currentColor;
  color: currentColor;
  font-family: var(--font-utility);
  font-size: 8.5px;
  letter-spacing: 0.14em;
  opacity: 0.45;
  padding-bottom: 6px;
}

.poster__title {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 13px;
  color: #23282a;
  font-family: var(--font-reading);
  font-size: 15px;
  letter-spacing: -0.01em;
  line-height: 1.26;
  text-align: center;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.poster__meta {
  margin-top: 5px;
  color: #5c625d;
  font-size: 8.5px;
  letter-spacing: 0.02em;
  line-height: 1.4;
  opacity: 0.8;
  text-align: center;
}

.poster__abstract {
  height: 21px;
  margin-top: 9px;
  background-image: repeating-linear-gradient(180deg, currentColor 0 1.5px, transparent 1.5px 5.5px);
  opacity: 0.34;
}

.poster__cols {
  display: grid;
  flex: 1;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
  margin-top: 11px;
}

.poster__cols i {
  background-image: repeating-linear-gradient(180deg, currentColor 0 1.5px, transparent 1.5px 5.5px);
  opacity: 0.34;
}

.poster__page {
  margin-top: 7px;
  color: currentColor;
  font-family: var(--font-utility);
  font-size: 7.5px;
  letter-spacing: 0.08em;
  opacity: 0.4;
  text-align: center;
}

@keyframes wall-up {
  from {
    transform: translateY(0);
  }

  to {
    transform: translateY(-50%);
  }
}

@keyframes wall-down {
  from {
    transform: translateY(-50%);
  }

  to {
    transform: translateY(0);
  }
}

/* ---- Right: the login pane ------------------------------------------- */
.auth-pane {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  border-left: 1px solid #e9e9e1;
  background-color: #fff;
  padding: 48px clamp(24px, 3.5vw, 48px);
}

.auth-card {
  width: 100%;
  max-width: 340px;
  animation: rise 620ms cubic-bezier(0.16, 1, 0.3, 1) 70ms both;
}

.auth-card__head {
  margin-bottom: 24px;
}

.auth-card__title {
  margin: 0;
  color: #17181a;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.015em;
  line-height: 1.35;
}

.auth-card__hint {
  margin: 4px 0 0;
  color: #6e756f;
  font-size: 12.5px;
  line-height: 1.5;
}

.auth-pane__locale {
  position: absolute;
  top: 18px;
  right: 18px;
  color: #6e756f;
}

/* ---- Fields ---------------------------------------------------------- */
.auth-card :deep(.n-input) {
  height: 44px;
  border-radius: 8px;
  background-color: #fff;
  color: #17181a;
}

.auth-card :deep(.n-input .n-input__input-el) {
  height: 44px;
  line-height: 44px;
  color: #17181a;
}

.auth-card :deep(.n-input .n-input__placeholder) {
  color: #6e756f !important;
}

.auth-card :deep(.n-input .n-input__border) {
  border: 1px solid #e0e0d7;
}

.auth-card :deep(.n-input:hover .n-input__border) {
  border-color: #c8c8bd;
}

.auth-card :deep(.n-input.n-input--focus .n-input__state-border) {
  border: 1px solid #17181a;
  box-shadow: 0 0 0 3px rgba(23, 24, 26, 0.06);
}

.auth-card :deep(.n-input .n-input__prefix),
.auth-card :deep(.n-input .n-input__eye) {
  color: #a8ada8;
}

.auth-card :deep(.n-checkbox--checked .n-checkbox-icon) {
  color: #fff;
}

.auth-card :deep(.n-checkbox__label) {
  color: #6e756f;
  font-size: 12.5px;
}

/* ---- Buttons --------------------------------------------------------- */
.auth-card :deep(.n-button.login-submit) {
  height: 44px;
  border-radius: 8px;
  background-color: #17181a;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.auth-card :deep(.n-button.login-submit:hover) {
  background-color: #2b2d30;
}

.auth-card :deep(.login-alt .n-button) {
  color: #6e756f;
}

.auth-card :deep(.login-alt .n-button:hover) {
  color: #17181a;
}

.auth-card :deep(.login-alt__dot),
.auth-card :deep(.login-legal) {
  color: #6e756f;
}

.auth-card :deep(.login-legal .n-button) {
  color: var(--color-research);
}

.auth-card :deep(.login-alt .n-button),
.auth-card :deep(.login-legal .n-button) {
  min-height: 30px;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 960px) {
  .landing {
    height: auto;
    min-height: 100dvh;
    grid-template-columns: minmax(0, 1fr);
    overflow: visible;
  }

  .landing__story {
    overflow: visible;
  }

  .story__layer {
    gap: var(--space-5);
    height: auto;
    padding: var(--space-8) var(--space-6) 0;
  }

  .veil {
    display: none;
  }

  .story__copy {
    padding-right: 0;
  }

  .story__title {
    font-size: 30px;
  }

  .story__lede {
    margin-top: 12px;
    font-size: 14px;
  }

  .wall {
    display: none;
  }

  .auth-pane {
    align-items: flex-start;
    border-top: 1px solid #e9e9e1;
    border-left: 0;
    padding: var(--space-8) var(--space-5) var(--space-12);
  }

  .auth-card {
    max-width: none;
  }

  .auth-pane__locale {
    top: 12px;
    right: 14px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .story__title,
  .story__lede,
  .wall,
  .auth-card {
    animation: none;
  }

  .wall__track {
    animation: none;
  }
}
</style>
