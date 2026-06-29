type UploadCandidate = Pick<File, 'name' | 'size'>;

export function getUploadFileValidationError(file: UploadCandidate | null | undefined) {
  if (!file) {
    return '请选择论文 PDF';
  }
  if (!Number.isFinite(file.size) || file.size <= 0) {
    return '文件内容为空，请选择有效 PDF';
  }
  return '';
}
