<script setup lang="ts">
import { reactive } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const loginAction = ref<'account' | 'guest' | null>(null);

interface FormModel {
  userName: string;
  password: string;
  rememberMe: boolean;
}

type FormRuleModel = Pick<FormModel, 'userName' | 'password'>;

const rememberedLogin = localStg.get('rememberedLogin');

const model: FormModel = reactive({
  userName: rememberedLogin?.userName || '',
  password: rememberedLogin?.password || '',
  rememberMe: Boolean(rememberedLogin)
});

const rules = computed<Record<keyof FormRuleModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

async function handleSubmit() {
  if (loginAction.value) return;

  await validate();
  loginAction.value = 'account';

  try {
    const success = await authStore.login(model.userName, model.password);

    if (!success) return;

    if (model.rememberMe) {
      localStg.set('rememberedLogin', {
        userName: model.userName,
        password: model.password
      });
    } else {
      localStg.remove('rememberedLogin');
    }
  } finally {
    loginAction.value = null;
  }
}

async function handleGuestLogin() {
  if (loginAction.value) return;

  loginAction.value = 'guest';
  try {
    await authStore.guestLogin();
  } finally {
    loginAction.value = null;
  }
}
</script>

<template>
  <NForm
    ref="formRef"
    :model="model"
    :rules="rules"
    size="large"
    :show-label="false"
    class="auth-form"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')">
        <template #prefix>
          <icon-lucide:graduation-cap />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-lucide:key-round />
        </template>
      </NInput>
    </NFormItem>
    <div class="mb-6 flex-y-center justify-between">
      <NCheckbox v-model:checked="model.rememberMe">
        {{ $t('page.login.pwdLogin.rememberMe') }}
      </NCheckbox>
    </div>
    <div class="flex-col gap-6">
      <NButton
        type="primary"
        size="large"
        round
        block
        :loading="loginAction === 'account'"
        :disabled="loginAction === 'guest'"
        @click="handleSubmit"
      >
        <template #icon>
          <icon-lucide:log-in />
        </template>
        {{ $t('page.login.common.login') }}
      </NButton>
      <NButton
        size="large"
        block
        class="guest-login-button"
        :class="{ 'is-loading': loginAction === 'guest' }"
        :disabled="loginAction === 'account'"
        :aria-busy="loginAction === 'guest'"
        @click="handleGuestLogin"
      >
        <template #icon>
          <icon-lucide:loader-circle v-if="loginAction === 'guest'" class="guest-login-spinner" />
          <icon-lucide:user-round v-else />
        </template>
        {{ $t('page.login.common.guestLogin') }}
      </NButton>
      <NButton block :disabled="loginAction !== null" @click="toggleLoginModule('register')">
        <template #icon>
          <icon-lucide:ticket />
        </template>
        {{ $t(loginModuleRecord.register) }}
      </NButton>

      <span class="text-center">
        登录即代表已阅读并同意我们的
        <NButton text type="primary">用户协议</NButton>
        和
        <NButton text type="primary">隐私政策</NButton>
      </span>
    </div>
  </NForm>
</template>

<style scoped>
.auth-form {
  color: var(--color-text);
}

:deep(.n-input) {
  border-radius: 6px;
  background: var(--color-card-band);
}

:deep(.n-input .n-input__border),
:deep(.n-input .n-input__state-border) {
  border-color: var(--color-border);
}

:deep(.n-input .n-input__prefix) {
  color: var(--color-primary);
}

.guest-login-button {
  position: relative;
  overflow: hidden;
}

.guest-login-button::after {
  position: absolute;
  top: 0;
  bottom: 0;
  left: -32%;
  width: 28%;
  background: var(--color-research-soft-bg);
  content: '';
  opacity: 0;
  pointer-events: none;
}

.guest-login-button.is-loading::after {
  opacity: 0.8;
  animation: guest-login-progress 1.1s ease-in-out infinite;
}

.guest-login-spinner {
  animation: guest-login-spin 0.8s linear infinite;
}

@keyframes guest-login-progress {
  to {
    transform: translateX(475%);
  }
}

@keyframes guest-login-spin {
  to {
    transform: rotate(1turn);
  }
}

@media (prefers-reduced-motion: reduce) {
  .guest-login-button.is-loading::after,
  .guest-login-spinner {
    animation: none;
  }
}
</style>
