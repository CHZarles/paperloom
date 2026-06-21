// @unocss-include
import { getRgb } from '@sa/color';
import { DARK_CLASS } from '@/constants/app';
import { localStg } from '@/utils/storage';
import { toggleHtmlClass } from '@/utils/common';
import { $t } from '@/locales';

export function setupLoading() {
  let themeColor = localStg.get('themeColor') || '#1a3a2e';
  if (themeColor.toLowerCase() === '#2f5d62') {
    themeColor = '#1a3a2e';
    localStg.set('themeColor', themeColor);
  }

  const darkMode = localStg.get('darkMode') || false;
  const { r, g, b } = getRgb(themeColor);

  const primaryColor = `--primary-color: ${r} ${g} ${b}`;

  if (darkMode) {
    toggleHtmlClass(DARK_CLASS).add();
  }

  const loadingClasses = [
    'left-0 top-0',
    'left-0 bottom-0 animate-delay-500',
    'right-0 top-0 animate-delay-1000',
    'right-0 bottom-0 animate-delay-1500'
  ];

  const dot = loadingClasses
    .map(item => {
      return `<div class="absolute w-16px h-16px bg-primary rounded-8px animate-pulse ${item}"></div>`;
    })
    .join('\n');

  const loading = `
<div class="fixed-center flex-col bg-layout" style="${primaryColor}">
  <div class="size-128px flex flex-col items-center justify-center border border-primary/28 bg-[rgb(255,255,255)] text-primary shadow-[8px_8px_0_rgba(226,232,240,0.9)]">
    <svg width="72" height="72" viewBox="0 0 72 72" aria-hidden="true" fill="none" xmlns="http://www.w3.org/2000/svg">
      <g transform="rotate(-4 36 36)">
        <rect x="15" y="15" width="44" height="44" rx="8" fill="rgb(201 193 178)" opacity=".82" transform="translate(4 4)"/>
        <rect x="16" y="9" width="12" height="54" rx="4" fill="rgb(251 250 246)" stroke="currentColor" stroke-width="3"/>
        <rect x="30" y="9" width="12" height="54" rx="4" fill="rgb(226 220 204)" stroke="currentColor" stroke-width="3"/>
        <rect x="44" y="9" width="12" height="54" rx="4" fill="rgb(251 250 246)" stroke="currentColor" stroke-width="3"/>
        <rect x="9" y="18" width="54" height="12" rx="4" fill="rgb(226 220 204)" stroke="currentColor" stroke-width="3"/>
        <rect x="9" y="31" width="54" height="12" rx="4" fill="rgb(231 221 224)" stroke="currentColor" stroke-width="3"/>
        <rect x="9" y="44" width="54" height="12" rx="4" fill="rgb(226 220 204)" stroke="currentColor" stroke-width="3"/>
        <rect x="16" y="31" width="12" height="12" rx="3" fill="rgb(251 250 246)" stroke="currentColor" stroke-width="3"/>
        <rect x="30" y="18" width="12" height="12" rx="3" fill="rgb(226 220 204)" stroke="currentColor" stroke-width="3"/>
        <rect x="30" y="44" width="12" height="12" rx="3" fill="rgb(226 220 204)" stroke="currentColor" stroke-width="3"/>
        <rect x="44" y="31" width="12" height="12" rx="3" fill="rgb(251 250 246)" stroke="currentColor" stroke-width="3"/>
      </g>
    </svg>
    <div class="mt-2 text-12px font-mono tracking-0">WOVEN EVIDENCE</div>
  </div>
  <div class="w-56px h-56px my-34px">
    <div class="relative h-full animate-spin">
      ${dot}
    </div>
  </div>
  <h2 class="font-serif text-28px font-700 text-primary">${$t('system.title')}</h2>
</div>`;

  const app = document.getElementById('app');

  if (app) {
    app.innerHTML = loading;
  }
}
