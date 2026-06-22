/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#1a3a2e',
  otherColor: { info: '#0ea5e9', success: '#22c55e', warning: '#f59e0b', error: '#ef4444' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 180,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'CiteWeave' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(248, 250, 252)',
        inverted: 'rgb(34, 39, 48)',
        'base-text': 'rgb(32, 36, 42)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(226, 232, 240, 0.7)',
        sider: '1px 0 0 0 rgb(226, 232, 240, 0.8)',
        tab: '0 1px 0 rgb(226, 232, 240, 0.7)'
      }
    },
    dark: { colors: { container: 'rgb(22, 26, 33)', layout: 'rgb(16, 19, 24)', 'base-text': 'rgb(237, 233, 223)' } }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
