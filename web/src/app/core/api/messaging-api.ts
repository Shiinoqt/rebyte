import { Injectable } from '@angular/core';
import { ApiClient } from './api-client';
import {
  ChatImageUploadPolicyRequest,
  ChatImageUploadPolicyResponse,
  ConversationPage,
  ConversationView,
  CursorQuery,
  EmailPreferenceView,
  MessagePage,
  MessageView,
  NotificationPage,
  NotificationQuery,
  NotificationView,
  ReadAllResult,
  SendMessageRequest,
  StartConversationRequest,
  UnreadSummary,
  UpdateEmailPreferenceRequest,
} from './api-models';

@Injectable({ providedIn: 'root' })
export class MessagingApi {
  constructor(private readonly api: ApiClient) {}

  conversations(query: CursorQuery = {}) {
    return this.api.get<ConversationPage>('/conversations', { params: { ...query } });
  }
  startConversation(request: StartConversationRequest) {
    return this.api.post<ConversationView>('/conversations', request);
  }
  conversation(id: string) {
    return this.api.get<ConversationView>(`/conversations/${encodeURIComponent(id)}`);
  }
  sendMessage(conversationId: string, request: SendMessageRequest) {
    return this.api.post<MessageView>(
      `/conversations/${encodeURIComponent(conversationId)}/messages`,
      request,
    );
  }
  messages(conversationId: string, query: CursorQuery = {}) {
    return this.api.get<MessagePage>(
      `/conversations/${encodeURIComponent(conversationId)}/messages`,
      { params: { ...query } },
    );
  }
  imageUploadPolicy(request: ChatImageUploadPolicyRequest) {
    return this.api.post<ChatImageUploadPolicyResponse>('/messages/image-upload-policy', request);
  }
  notifications(query: NotificationQuery = {}) {
    return this.api.get<NotificationPage>('/notifications', { params: { ...query } });
  }
  unreadSummary() {
    return this.api.get<UnreadSummary>('/notifications/unread-summary');
  }
  readNotification(id: string) {
    return this.api.post<NotificationView>(`/notifications/${encodeURIComponent(id)}/read`);
  }
  readAllNotifications() {
    return this.api.post<ReadAllResult>('/notifications/read-all');
  }
  deleteNotification(id: string) {
    return this.api.delete<void>(`/notifications/${encodeURIComponent(id)}`);
  }
  deleteReadNotifications() {
    return this.api.delete<void>('/notifications', { params: { read: true } });
  }
  emailPreferences() {
    return this.api.get<EmailPreferenceView>('/notifications/email-preferences');
  }
  updateEmailPreferences(request: UpdateEmailPreferenceRequest) {
    return this.api.put<EmailPreferenceView>('/notifications/email-preferences', request);
  }
  unsubscribeConfirmation(token: string) {
    return this.api.getText('/notifications/email-preferences/unsubscribe', { params: { token } });
  }
  unsubscribe(token: string) {
    return this.api.postText('/notifications/email-preferences/unsubscribe', null, {
      params: { token },
    });
  }
  replayDeadLetter(queue: string) {
    return this.api.postResponse<void>(
      `/admin/messaging/dead-letters/${encodeURIComponent(queue)}/replay`,
    );
  }
}
