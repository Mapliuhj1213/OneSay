export default class WebSocket { static OPEN = 1 }
export class WebSocketServer {
  constructor() { this.clients = new Set() }
  handleUpgrade() {}
  close() {}
}
