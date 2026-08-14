/**
 * FastBox preload 桥接
 * 通过 contextBridge 暴露最小 API，渲染进程不直接接触 Node
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fastbox', {
  /** 调用 Java 网关，返回 {status, body} */
  call: (method, endpoint, body) => ipcRenderer.invoke('gateway:call', method, endpoint, body),
  /** 执行 JS 插件（本地，不经网关），返回 {detail?, toast?} */
  executeJs: (payload) => ipcRenderer.invoke('plugin:execute-js', payload),
  /** 隐藏面板 */
  hide: () => ipcRenderer.invoke('panel:hide'),
  /** 订阅事件：panel:shown / status:gateway / status:toast */
  on: (channel, cb) => {
    ipcRenderer.on(channel, (_evt, data) => cb(data));
  },
});
