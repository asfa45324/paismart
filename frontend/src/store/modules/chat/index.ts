import { watch } from 'vue';
import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);

  const store = useAuthStore();

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true,
    reconnectDelay: 3000,
    maxReconnectAttempts: 5,
    // 页面可见性变化时的处理
    onOpen: () => {
      console.log('WebSocket 连接已打开');
    },
    onClose: () => {
      console.log('WebSocket 连接已关闭');
    },
    onError: (error) => {
      console.error('WebSocket 连接出错:', error);
    }
  });

  // 监听页面可见性变化，确保连接状态正常 - 只在浏览器环境下执行
  if (typeof window !== 'undefined') {
    watch(
      () => document.visibilityState,
      (state) => {
        if (state === 'visible' && wsStatus.value === 'CLOSED') {
          wsOpen();
        }
      }
    );
  }

  // 监听 WebSocket 数据，更新 AI 消息
  watch(wsData, val => {
    if (val) {
      try {
        const data = JSON.parse(val);
        const assistant = list.value[list.value.length - 1];
        
        if (assistant && assistant.role === 'assistant') {
          if (data.type === 'completion' && data.status === 'finished' && assistant.status !== 'error') {
            assistant.status = 'finished';
          }
          if (data.error) {
            assistant.status = 'error';
          }
          else if (data.chunk) {
            assistant.status = 'loading';
            assistant.content += data.chunk;
          }
        }
      }
      catch (e) {
        console.error('Failed to parse WebSocket data:', e);
      }
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
    list,
    wsStatus,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom
  };
});
