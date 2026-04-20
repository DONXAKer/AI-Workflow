import { Client, IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { WsMessage } from '../types'

type MessageHandler = (msg: WsMessage) => void

export function connectToRun(runId: string, onMessage: MessageHandler, onConnect?: () => void): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    onConnect: () => {
      onConnect?.()
      client.subscribe(`/topic/runs/${runId}`, (msg: IMessage) => {
        try {
          onMessage(JSON.parse(msg.body) as WsMessage)
        } catch {
          // ignore malformed messages
        }
      })
    },
    reconnectDelay: 3000,
  })
  client.activate()
  return () => { client.deactivate() }
}

export function connectToGlobalRuns(onMessage: MessageHandler, onConnect?: () => void): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    onConnect: () => {
      onConnect?.()
      client.subscribe('/topic/runs', (msg: IMessage) => {
        try {
          onMessage(JSON.parse(msg.body) as WsMessage)
        } catch {
          // ignore malformed messages
        }
      })
    },
    reconnectDelay: 3000,
  })
  client.activate()
  return () => { client.deactivate() }
}
