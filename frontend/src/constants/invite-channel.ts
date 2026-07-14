export function buildInviteChannelGuide() {
  return [
    'Folio 当前采用邀请码注册。',
    '请联系工作区管理员获取有效邀请码。',
    '收到邀请码后，回到注册页继续完成注册。'
  ].join('\n');
}

export function buildInviteCodeShareMessage(shareLink: string, inviteCode: string) {
  return [
    'Folio 邀请',
    `邀请码：${inviteCode}`,
    `注册链接：${shareLink}`,
    '',
    '如果邀请码失效，请联系工作区管理员重新获取。'
  ].join('\n');
}
