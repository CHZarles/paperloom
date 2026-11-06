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
    <div class="login-actions">
      <NButton
        type="primary"
        size="large"
        block
        class="login-submit"
        :loading="loginAction === 'account'"
        :disabled="loginAction === 'guest'"
        @click="handleSubmit"
      >
        <template #icon>
          <icon-lucide:log-in />
        </template>
        {{ $t('page.login.common.login') }}
      </NButton>

      <div class="login-alt">
        <NButton
          text
          size="small"
          :loading="loginAction === 'guest'"
          :disabled="loginAction === 'account'"
          @click="handleGuestLogin"
        >
          {{ $t('page.login.common.guestLogin') }}
        </NButton>
        <span class="login-alt__dot">·</span>
        <NButton text size="small" :disabled="loginAction !== null" @click="toggleLoginModule('register')">
          {{ $t(loginModuleRecord.register) }}
        </NButton>
      </div>

      <p class="login-legal">
        登录即代表已阅读并同意我们的
        <NButton text type="primary" size="tiny">用户协议</NButton>
        和
        <NButton text type="primary" size="tiny">隐私政策</NButton>
      </p>
    </div>
  </NForm>
</template>

<style scoped>
.auth-form {
  color: inherit;
}

.login-actions {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-alt {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.login-alt__dot {
  font-size: 11px;
}

.login-legal {
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.65;
  text-align: center;
}
</style>
