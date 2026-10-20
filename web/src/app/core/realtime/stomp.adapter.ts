import { Injectable } from '@angular/core';
import { Client, StompConfig } from '@stomp/stompjs';

@Injectable({ providedIn: 'root' })
export class StompAdapter {
  createClient(config: StompConfig): Client {
    return new Client(config);
  }
}
