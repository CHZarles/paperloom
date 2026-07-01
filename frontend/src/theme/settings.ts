/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#1a1a19',
  otherColor: { info: '#5e5e5b', success: '#168039', warning: '#b7791f', error: '#d92d20' },
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
        layout: 'rgb(247, 247, 245)',
        inverted: 'rgb(240, 240, 240)',
        'base-text': 'rgb(52, 50, 45)'
      },
      boxShadow: {
        header: '0 1px 0 rgb(229, 228, 225, 0.85)',
        sider: '1px 0 0 0 rgb(229, 228, 225, 0.85)',
        tab: '0 1px 0 rgb(229, 228, 225, 0.85)'
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
