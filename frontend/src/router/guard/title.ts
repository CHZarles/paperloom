import type { Router } from 'vue-router';
import { useTitle } from '@vueuse/core';
import { $t } from '@/locales';

export function createDocumentTitleGuard(router: Router) {
  router.afterEach(to => {
    const { i18nKey, title } = to.meta;

    const documentTitle = i18nKey ? $t(i18nKey) : title;
    const appTitle = $t('system.title');
    const fullTitle = documentTitle && documentTitle !== appTitle ? `${documentTitle} - ${appTitle}` : appTitle;

    useTitle(fullTitle);
  });
}
