import assert from 'node:assert/strict';
import { getUploadFileValidationError } from '../src/store/modules/knowledge-base/upload-validation';

assert.equal(getUploadFileValidationError(null), '请选择论文 PDF');
assert.equal(getUploadFileValidationError({ name: 'empty.pdf', size: 0 }), '文件内容为空，请选择有效 PDF');
assert.equal(getUploadFileValidationError({ name: 'paper.pdf', size: 1024 }), '');
