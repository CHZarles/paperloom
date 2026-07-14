<script setup lang="ts">
import { normalizeBackendMessage } from '@/service/request/shared';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const route = useRoute();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

const inviteCodeErrorCodes = new Set([
  'INVITE_CODE_REQUIRED',
  'INVITE_CODE_INVALID',
  'INVITE_CODE_EXPIRED',
  'INVITE_CODE_EXHAUSTED'
]);

interface FormModel {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

const model: FormModel = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  inviteCode: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, defaultRequiredRule, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password),
    inviteCode: [defaultRequiredRule]
  };
});

const loading = ref(false);
const inviteCodeErrorMessage = ref('');

function clearInviteCodeError() {
  inviteCodeErrorMessage.value = '';
}

function resolveRegisterError(error: any) {
  const rawMessage = String(error?.response?.data?.message || error?.message || '');
  const message = normalizeBackendMessage(rawMessage || '注册失败，请稍后重试');

  return {
    rawMessage,
    message
  };
}

async function handleSubmit() {
  clearInviteCodeError();
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password, model.inviteCode.trim());
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
  } else {
    const { rawMessage, message } = resolveRegisterError(error);

    if (inviteCodeErrorCodes.has(rawMessage)) {
      inviteCodeErrorMessage.value = message;
    }
  }
  loading.value = false;
}

function syncInviteCodeFromQuery(inviteCode: unknown) {
  if (typeof inviteCode !== 'string') return;
  model.inviteCode = inviteCode.trim();
}

watch(
  () => route.query.inviteCode,
  inviteCode => {
    syncInviteCodeFromQuery(inviteCode);
  },
  { immediate: true }
);

watch(
  () => model.inviteCode,
  () => {
    if (inviteCodeErrorMessage.value) {
      clearInviteCodeError();
    }
  }
);
</script>

<template>
  <div class="register-layout">
    <NForm
      ref="formRef"
      :model="model"
      :rules="rules"
      size="large"
      :show-label="false"
      class="register-form-panel"
      @keyup.enter="handleSubmit"
    >
      <NFormItem path="username">
        <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
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
      <NFormItem path="confirmPassword">
        <NInput
          v-model:value="model.confirmPassword"
          type="password"
          show-password-on="click"
          :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
        >
          <template #prefix>
            <icon-lucide:lock-open />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem
        path="inviteCode"
        :validation-status="inviteCodeErrorMessage ? 'error' : undefined"
        :feedback="inviteCodeErrorMessage || undefined"
      >
        <NInput v-model:value="model.inviteCode" :placeholder="$t('page.login.common.inviteCodePlaceholder')">
          <template #prefix>
            <icon-lucide:ticket />
          </template>
        </NInput>
      </NFormItem>
      <div class="register-form-tip mb-4">
        {{ $t('page.login.register.inviteCodeTip') }}
      </div>
      <NSpace vertical :size="18" class="w-full">
        <NButton type="primary" size="large" round block :loading="loading" @click="handleSubmit">
          <template #icon>
            <icon-lucide:user-plus />
          </template>
          {{ $t('page.login.common.register') }}
        </NButton>
        <NButton block @click="toggleLoginModule('pwd-login')">
          <template #icon>
            <icon-lucide:arrow-left />
          </template>
          {{ $t('page.login.common.back') }}
        </NButton>
      </NSpace>

      <div class="mt-4 text-center">
        {{ $t('page.login.register.agreement') }}
        <NButton text type="primary">{{ $t('page.login.register.protocol') }}</NButton>
        {{ $t('page.login.register.and') }}
        <NButton text type="primary">{{ $t('page.login.register.policy') }}</NButton>
      </div>
    </NForm>
  </div>
</template>

<style scoped>
.register-layout {
  width: 100%;
}

.register-form-panel {
  min-width: 0;
}

.register-form-panel :deep(.n-input) {
  border-radius: 6px;
  background: var(--color-card-band);
}

.register-form-panel :deep(.n-input .n-input__border),
.register-form-panel :deep(.n-input .n-input__state-border) {
  border-color: var(--color-border);
}

.register-form-panel :deep(.n-input .n-input__prefix) {
  color: var(--color-primary);
}

.register-form-tip {
  font-size: 12px;
  line-height: 1.7;
  color: rgb(var(--base-text-color) / 0.66);
}
</style>
