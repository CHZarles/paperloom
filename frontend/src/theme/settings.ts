/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#202522',
  otherColor: { info: '#16786e', success: '#168039', warning: '#b7791f', error: '#d92d20' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 300,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'Folio' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(246, 248, 247)',
        inverted: 'rgb(240, 243, 241)',
        'base-text': 'rgb(32, 37, 34)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(220, 227, 223, 0.88)',
        sider: '1px 0 0 0 rgb(220, 227, 223, 0.88)',
        tab: '0 1px 0 rgb(220, 227, 223, 0.88)'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {
  themeScheme: 'light',
  tokens: themeSettings.tokens
};
