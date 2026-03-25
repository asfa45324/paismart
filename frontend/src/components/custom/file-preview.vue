<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import SvgIcon from '@/components/custom/svg-icon.vue';



interface Props {
  fileName: string;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');
const imageUrl = ref('');

// Office文档预览URL
const officePreviewUrl = computed(() => {
  if (!isOfficeFile.value || !imageUrl.value) return '';
  // 确保URL是完整的http或https链接
  let url = imageUrl.value;
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    // 如果是相对路径，添加完整的基础URL
    url = window.location.origin + url;
  }
  const encodedUrl = encodeURIComponent(url);
  const previewUrl = `https://view.officeapps.live.com/op/view.aspx?src=${encodedUrl}`;
  console.log('生成的Office Online预览链接:', previewUrl);
  return previewUrl;
});

// 获取文件扩展名
const fileExt = computed(() => {
  return getFileExt(props.fileName).toLowerCase();
});

// 判断文件类型
const isTextFile = computed(() => {
  const textExts = [
    'txt',
    'md',
    'json',
    'xml',
    'html',
    'htm',
    'css',
    'js',
    'ts',
    'py',
    'java',
    'c',
    'cpp',
    'h',
    'hpp',
    'php',
    'rb',
    'go',
    'rs',
    'sh',
    'bat',
    'cmd',
    'csv',
    'log',
    'yaml',
    'yml',
    'properties',
    'conf',
    'config',
    'sql',
    'dockerfile',
    'env',
    'gitignore',
    'md',
    'markdown'
  ];
  return textExts.includes(fileExt.value);
});

const isImageFile = computed(() => {
  const imageExts = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp', 'tiff', 'tif', 'ico'];
  return imageExts.includes(fileExt.value);
});

const isPdfFile = computed(() => {
  return fileExt.value === 'pdf';
});

const isOfficeFile = computed(() => {
  const officeExts = ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'ods', 'odp'];
  return officeExts.includes(fileExt.value);
});

const isAudioFile = computed(() => {
  const audioExts = ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a', 'wma'];
  return audioExts.includes(fileExt.value);
});

const isVideoFile = computed(() => {
  const videoExts = ['mp4', 'avi', 'mov', 'wmv', 'flv', 'mkv', 'webm'];
  return videoExts.includes(fileExt.value);
});

// 获取文件图标
function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

// 监听文件名变化，加载预览内容
watch(
  () => props.fileName,
  async newFileName => {
    if (newFileName && props.visible) {
      await loadPreviewContent();
    }
  },
  { immediate: true }
);

// 监听可见性变化
watch(
  () => props.visible,
  async visible => {
    if (visible && props.fileName) {
      await loadPreviewContent();
    }
  }
);

// 加载预览内容
async function loadPreviewContent() {
  if (!props.fileName) return;

  loading.value = true;
  error.value = '';
  content.value = '';
  imageUrl.value = '';

  try {
    // 只对文本文件加载预览内容
    if (isTextFile.value) {
      const { error: requestError, data } = await request<{
        fileName: string;
        content: string;
        fileSize: number;
      }>({
        url: '/documents/preview',
        params: {
          fileName: props.fileName
        }
      });

      if (requestError) {
        error.value = `预览失败：${requestError.message || '未知错误'}`;
      } else if (data) {
        content.value = data.content || '无预览内容';
      }
    }
    // 对图片、音频、视频、PDF文件处理
    else if (isImageFile.value || isAudioFile.value || isVideoFile.value || isPdfFile.value) {
      // 对于PDF文件，使用专门的预览链接
      if (isPdfFile.value) {
        console.log('开始获取PDF文件预览链接:', props.fileName);
        const { error: requestError, data } = await request<{
          fileName: string;
          previewUrl: string;
          fileSize: number;
        }>({
          url: '/documents/preview-url',
          params: {
            fileName: props.fileName
          }
        });

        if (requestError) {
          error.value = `预览失败：${requestError.message || '未知错误'}`;
          console.error('PDF文件预览链接获取失败:', requestError);
        } else if (data) {
          console.log('获取到的PDF预览链接:', data.previewUrl);
          imageUrl.value = data.previewUrl;
          console.log('PDF预览URL:', imageUrl.value);
          // 测试URL是否可访问
          fetch(imageUrl.value, { method: 'HEAD' })
            .then(response => {
              console.log('URL访问状态:', response.status);
              if (response.status === 200) {
                console.log('URL可访问');
              } else {
                console.log('URL访问失败:', response.status);
              }
            })
            .catch(error => {
              console.error('URL访问错误:', error);
            });
        }
      } else {
        // 对于其他文件类型，使用下载链接
        console.log('开始获取文件下载链接:', props.fileName);
        const { error: requestError, data } = await request<{
          fileName: string;
          downloadUrl: string;
          fileSize: number;
        }>({
          url: '/documents/download',
          params: {
            fileName: props.fileName
          }
        });

        if (requestError) {
          error.value = `预览失败：${requestError.message || '未知错误'}`;
          console.error('文件下载链接获取失败:', requestError);
        } else if (data) {
          console.log('获取到的下载链接:', data.downloadUrl);
          imageUrl.value = data.downloadUrl;
          // 测试URL是否可访问
          fetch(imageUrl.value, { method: 'HEAD' })
            .then(response => {
              console.log('URL访问状态:', response.status);
              if (response.status === 200) {
                console.log('URL可访问');
              } else {
                console.log('URL访问失败:', response.status);
              }
            })
            .catch(error => {
              console.error('URL访问错误:', error);
            });
        }
      }
    }
    // 对Office文件处理
    else if (isOfficeFile.value) {
      // Office文件需要获取下载链接用于Office Online预览
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
      }>({
        url: '/documents/download',
        params: {
          fileName: props.fileName
        }
      });

      if (requestError) {
        error.value = `预览失败：${requestError.message || '未知错误'}`;
        console.error('Office文件下载链接获取失败:', requestError);
      } else if (data) {
        console.log('获取到的Office文件下载链接:', data.downloadUrl);
        console.log('生成的Office Online预览链接:', officePreviewUrl.value);
        imageUrl.value = data.downloadUrl;
      }
    }
    // 其他文件类型不需要加载内容
  } catch (err: any) {
    error.value = `预览失败：${err.message || '网络错误'}`;
  } finally {
    loading.value = false;
  }
}

// 下载文件
async function downloadFile() {
  if (!props.fileName) return;

  downloading.value = true;

  try {
    const { error: requestError, data } = await request<{
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }>({
      url: '/documents/download',
      params: {
        fileName: props.fileName
      }
    });

    if (requestError) {
      window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
    } else if (data) {
      // 使用预签名URL下载文件
      const link = document.createElement('a');
      link.href = data.downloadUrl;
      link.download = data.fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.$message?.success('开始下载文件');
    }
  } catch (err: any) {
    window.$message?.error(`下载失败：${err.message || '网络错误'}`);
  } finally {
    downloading.value = false;
  }
}

// 关闭预览
function closePreview() {
  // 清理PDF实例
  if (pdfInstance.value) {
    pdfInstance.value.destroy();
    pdfInstance.value = null;
  }
  emit('close');
}

// 切换PDF页面
function changePage(direction: 'prev' | 'next') {
  // 预留方法，用于未来可能的PDF.js实现
}
</script>

<template>
  <div class="file-preview-container">
    <!-- 预览头部 -->
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" :loading="downloading" @click="downloadFile">
          <template #icon>
            <icon-mdi-download />
          </template>
          下载
        </NButton>
        <NButton size="small" @click="closePreview">
          <template #icon>
            <icon-mdi-close />
          </template>
        </NButton>
      </div>
    </div>

    <!-- 预览内容 -->
    <div class="preview-content">
      <template v-if="loading">
        <div class="h-full flex items-center justify-center">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="h-full flex flex-col items-center justify-center text-gray-500">
          <icon-mdi-alert-circle class="mb-4 text-48" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else>
        <div class="content-wrapper">
          <!-- 根据文件类型显示不同的预览内容 -->
          <template v-if="isTextFile">
            <!-- 文本文件直接显示 -->
            <pre class="preview-text">{{ content || '无预览内容' }}</pre>
          </template>
          <template v-else-if="isImageFile">
            <!-- 图片文件显示图片 -->
            <div class="image-preview-container">
              <img :src="imageUrl" alt="文件预览" class="image-preview" />
            </div>
          </template>
          <template v-else-if="isPdfFile">
            <!-- PDF文件显示PDF预览 -->
            <div class="pdf-preview-container">
              <div v-if="!imageUrl" class="h-full flex items-center justify-center">
                <NSpin size="large" />
              </div>
              <div v-else-if="error" class="h-full flex items-center justify-center">
                <div class="text-center">
                  <icon-mdi-alert-circle class="mb-4 text-48 text-red-500" />
                  <p>{{ error }}</p>
                  <NButton type="primary" @click="downloadFile" class="mt-4">
                    <template #icon>
                      <icon-mdi-download />
                    </template>
                    下载查看
                  </NButton>
                </div>
              </div>
              <div v-else class="pdf-content">
                <iframe 
                  :src="imageUrl" 
                  class="pdf-iframe"
                  frameborder="0"
                  allowfullscreen
                ></iframe>
                <div class="pdf-controls">
                  <NButton type="primary" @click="downloadFile">
                    <template #icon>
                      <icon-mdi-download />
                    </template>
                    下载
                  </NButton>
                </div>
              </div>
            </div>
          </template>
          <template v-else-if="isOfficeFile">
            <!-- Office文件显示Office Online预览 -->
            <div class="office-preview-container">
              <div v-if="!imageUrl" class="h-full flex items-center justify-center">
                <NSpin size="large" />
              </div>
              <div v-else-if="error" class="h-full flex items-center justify-center">
                <div class="text-center">
                  <icon-mdi-alert-circle class="mb-4 text-48 text-red-500" />
                  <p>{{ error }}</p>
                  <NButton type="primary" @click="downloadFile" class="mt-4">
                    <template #icon>
                      <icon-mdi-download />
                    </template>
                    下载查看
                  </NButton>
                </div>
              </div>
              <div v-else class="office-content">
                <iframe 
                  :src="officePreviewUrl" 
                  class="office-iframe"
                  frameborder="0"
                  allowfullscreen
                ></iframe>
                <div class="office-controls">
                  <NButton type="primary" @click="downloadFile">
                    <template #icon>
                      <icon-mdi-download />
                    </template>
                    下载
                  </NButton>
                </div>
              </div>
            </div>
          </template>
          <template v-else-if="isAudioFile">
            <!-- 音频文件预览 -->
            <div class="audio-preview-container">
              <div class="audio-placeholder">
                <icon-mdi-music-box class="mb-4 text-64 text-blue-500" />
                <h3 class="mb-2 text-lg font-medium">{{ fileName }}</h3>
                <p class="mb-4 text-gray-500">音频文件预览</p>
                <audio controls class="mb-4 w-full max-w-md">
                  <source :src="imageUrl" :type="`audio/${fileExt}`">
                  您的浏览器不支持音频播放。
                </audio>
                <NButton type="primary" @click="downloadFile">
                  <template #icon>
                    <icon-mdi-download />
                  </template>
                  下载
                </NButton>
              </div>
            </div>
          </template>
          <template v-else-if="isVideoFile">
            <!-- 视频文件预览 -->
            <div class="video-preview-container">
              <div class="video-placeholder">
                <icon-mdi-movie class="mb-4 text-64 text-green-500" />
                <h3 class="mb-2 text-lg font-medium">{{ fileName }}</h3>
                <p class="mb-4 text-gray-500">视频文件预览</p>
                <video controls class="mb-4 w-full max-w-2xl">
                  <source :src="imageUrl" :type="`video/${fileExt}`">
                  您的浏览器不支持视频播放。
                </video>
                <NButton type="primary" @click="downloadFile">
                  <template #icon>
                    <icon-mdi-download />
                  </template>
                  下载
                </NButton>
              </div>
            </div>
          </template>
          <template v-else>
            <!-- 其他文件类型显示提示 -->
            <div class="other-preview-container">
              <div class="other-placeholder">
                <icon-mdi-file class="mb-4 text-64 text-gray-400" />
                <h3 class="mb-2 text-lg font-medium">{{ fileName }}</h3>
                <p class="mb-4 text-gray-500">不支持的文件预览类型</p>
                <NButton type="primary" @click="downloadFile">
                  <template #icon>
                    <icon-mdi-download />
                  </template>
                  下载查看
                </NButton>
              </div>
            </div>
          </template>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.file-preview-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  background-color: white;
  border-left: 1px solid #e5e7eb;

  .preview-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 1rem;
    border-bottom: 1px solid #e5e7eb;
    background-color: #f9fafb;
  }

  .preview-content {
    flex: 1;
    overflow: hidden;

    .content-wrapper {
      height: 100%;
      overflow: auto;
      padding: 1rem;
    }

    /* 文本文件预览样式 */
    .preview-text {
      font-size: 0.875rem;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      white-space: pre-wrap;
      word-break: break-all;
      line-height: 1.5;
      margin: 0;
      color: #374151;
      background-color: white;
    }

    /* 图片文件预览样式 */
    .image-preview-container {
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .image-preview {
        max-width: 100%;
        max-height: 100%;
        object-fit: contain;
        border-radius: 0.25rem;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
      }
    }

    /* PDF文件预览样式 */
    .pdf-preview-container {
      display: flex;
      flex-direction: column;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .pdf-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 2rem;
        height: 100%;
      }

      .pdf-content {
        display: flex;
        flex-direction: column;
        height: 100%;

        .pdf-iframe {
          flex: 1;
          width: 100%;
          border: 1px solid #e5e7eb;
          border-radius: 0.25rem;
        }

        .pdf-controls {
          display: flex;
          justify-content: flex-end;
          padding: 1rem;
          border-top: 1px solid #e5e7eb;
        }
      }
    }

    /* Office文件预览样式 */
    .office-preview-container {
      display: flex;
      flex-direction: column;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .office-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 2rem;
        height: 100%;
      }

      .office-content {
        display: flex;
        flex-direction: column;
        height: 100%;

        .office-iframe {
          flex: 1;
          width: 100%;
          border: 1px solid #e5e7eb;
          border-radius: 0.25rem;
        }

        .office-controls {
          display: flex;
          justify-content: flex-end;
          padding: 1rem;
          border-top: 1px solid #e5e7eb;
        }
      }
    }

    /* 音频文件预览样式 */
    .audio-preview-container {
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .audio-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 2rem;
      }
    }

    /* 视频文件预览样式 */
    .video-preview-container {
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .video-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 2rem;
      }
    }

    /* 其他文件类型预览样式 */
    .other-preview-container {
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
      background-color: #f9fafb;
      border-radius: 0.5rem;

      .other-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 2rem;
      }
    }
  }
}
</style>
