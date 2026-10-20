import { Injectable } from '@angular/core';
import { ApiClient } from './api-client';
import {
  AvatarConfirmRequest,
  AvatarUploadPolicyResponse,
  DeveloperProfileResponse,
  MeResponse,
  PublicProfileResponse,
  UpdateProfileRequest,
  UsernameAvailabilityResponse,
} from './api-models';

@Injectable({ providedIn: 'root' })
export class UserApi {
  constructor(private readonly api: ApiClient) {}

  getMe() {
    return this.api.get<MeResponse>('/users/me');
  }
  updateProfile(request: UpdateProfileRequest) {
    return this.api.put<MeResponse>('/users/me', request);
  }
  getPublicProfile(sub: string) {
    return this.api.get<PublicProfileResponse>(`/users/${encodeURIComponent(sub)}`);
  }
  usernameAvailable(username: string) {
    return this.api.get<UsernameAvailabilityResponse>('/users/username-available', {
      params: { u: username },
    });
  }
  activateDeveloperProfile() {
    return this.api.post<DeveloperProfileResponse>('/users/me/developer-profile');
  }
  avatarUploadPolicy() {
    return this.api.post<AvatarUploadPolicyResponse>('/users/me/avatar/upload-policy');
  }
  confirmAvatar(request: AvatarConfirmRequest) {
    return this.api.post<MeResponse>('/users/me/avatar/confirm', request);
  }
  deleteAvatar() {
    return this.api.delete<void>('/users/me/avatar');
  }
  follow(sub: string) {
    return this.api.put<void>(`/users/${encodeURIComponent(sub)}/follow`);
  }
  unfollow(sub: string) {
    return this.api.delete<void>(`/users/${encodeURIComponent(sub)}/follow`);
  }
}
