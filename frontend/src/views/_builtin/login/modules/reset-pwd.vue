<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';

defineOptions({
  name: 'ResetPwd'
});

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  phone: string;
  code: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: ''
});

type RuleRecord = Partial<Record<keyof FormModel, App.Global.FormRule[]>>;

const rules = computed<RuleRecord>(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();

  return {
    phone: formRules.phone,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

async function handleSubmit() {
  await validate();
  // request to reset password
  window.$message?.success($t('page.login.common.validateSuccess'));
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
    <NFormItem path="phone">
      <NInput v-model:value="model.phone" :placeholder="$t('page.login.common.phonePlaceholder')">
        <template #prefix>
          <icon-material-symbols:phone-iphone-sharp />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="code">
      <NInput v-model:value="model.code" :placeholder="$t('page.login.common.codePlaceholder')">
        <template #prefix>
          <icon-material-symbols:vpn-key-alert-outline-rounded />
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
          <icon-material-symbols:vpn-key-outline-rounded />
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
          <icon-material-symbols:lock-open-outline-rounded />
        </template>
      </NInput>
    </NFormItem>
    <NSpace vertical :size="18" class="w-full">
      <NButton type="primary" size="large" round block @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
      <NButton size="large" round block @click="toggleLoginModule('pwd-login')">
        {{ $t('page.login.common.back') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped>
.auth-form :deep(.n-input) {
  border-radius: 6px;
  background: var(--color-card-band);
}

.auth-form :deep(.n-input .n-input__border),
.auth-form :deep(.n-input .n-input__state-border) {
  border-color: var(--color-border);
}

.auth-form :deep(.n-input .n-input__prefix) {
  color: var(--color-primary);
}
</style>
