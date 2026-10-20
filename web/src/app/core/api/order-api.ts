import { Injectable } from '@angular/core';
import { ApiClient } from './api-client';
import {
  AdminProblemPage,
  AdminProblemQuery,
  CaptureResponse,
  CheckoutRequest,
  CheckoutResponse,
  CreateOfferRequest,
  CreateReviewRequest,
  CursorQuery,
  DismissProblemRequest,
  LicensePage,
  OfferPage,
  OfferQuery,
  OfferView,
  OrderPage,
  OrderView,
  ProblemView,
  RefundRequest,
  RefundResponse,
  ReportProblemRequest,
  ResolveProblemRequest,
  ReviewPage,
  ReviewQuery,
  ReviewView,
  ShipOrderRequest,
  StuckOrderPage,
} from './api-models';

@Injectable({ providedIn: 'root' })
export class OrderApi {
  constructor(private readonly api: ApiClient) {}

  checkout(request: CheckoutRequest, idempotencyKey: string) {
    return this.api.post<CheckoutResponse>('/orders', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }
  order(id: string) {
    return this.api.get<OrderView>(`/orders/${encodeURIComponent(id)}`);
  }
  purchases(query: CursorQuery = {}) {
    return this.api.get<OrderPage>('/orders/me/purchases', { params: { ...query } });
  }
  sales(query: CursorQuery = {}) {
    return this.api.get<OrderPage>('/orders/me/sales', { params: { ...query } });
  }
  cancelOrder(id: string) {
    return this.api.post<void>(`/orders/${encodeURIComponent(id)}/cancel`);
  }
  capturePayment(orderId: string) {
    return this.api.post<CaptureResponse>(`/payments/${encodeURIComponent(orderId)}/capture`);
  }
  shipOrder(id: string, request: ShipOrderRequest) {
    return this.api.post<OrderView>(`/orders/${encodeURIComponent(id)}/ship`, request);
  }
  deliverOrder(id: string) {
    return this.api.post<OrderView>(`/orders/${encodeURIComponent(id)}/deliver`);
  }
  reportProblem(orderId: string, request: ReportProblemRequest) {
    return this.api.post<ProblemView>(`/orders/${encodeURIComponent(orderId)}/problem`, request);
  }
  withdrawProblem(orderId: string) {
    return this.api.post<ProblemView>(`/orders/${encodeURIComponent(orderId)}/problem/withdraw`);
  }
  refundProblem(orderId: string, request: ResolveProblemRequest | null = null) {
    return this.api.post<ProblemView>(
      `/orders/${encodeURIComponent(orderId)}/problem/refund`,
      request,
    );
  }
  createReview(orderId: string, request: CreateReviewRequest) {
    return this.api.post<ReviewView>(`/orders/${encodeURIComponent(orderId)}/review`, request);
  }
  licenses(query: CursorQuery = {}) {
    return this.api.get<LicensePage>('/licences/me', { params: { ...query } });
  }
  review(id: string) {
    return this.api.get<ReviewView>(`/reviews/${encodeURIComponent(id)}`);
  }
  sellerReviews(sub: string, query: ReviewQuery = {}) {
    return this.api.get<ReviewPage>(`/users/${encodeURIComponent(sub)}/reviews`, {
      params: { ...query },
    });
  }
  createOffer(request: CreateOfferRequest) {
    return this.api.post<OfferView>('/offers', request);
  }
  offer(id: string) {
    return this.api.get<OfferView>(`/offers/${encodeURIComponent(id)}`);
  }
  sentOffers(query: OfferQuery = {}) {
    return this.api.get<OfferPage>('/offers/me/sent', { params: { ...query } });
  }
  receivedOffers(query: OfferQuery = {}) {
    return this.api.get<OfferPage>('/offers/me/received', { params: { ...query } });
  }
  acceptOffer(id: string) {
    return this.api.post<OfferView>(`/offers/${encodeURIComponent(id)}/accept`);
  }
  rejectOffer(id: string) {
    return this.api.post<OfferView>(`/offers/${encodeURIComponent(id)}/reject`);
  }
  withdrawOffer(id: string) {
    return this.api.post<OfferView>(`/offers/${encodeURIComponent(id)}/withdraw`);
  }
  adminRefundOrder(id: string, request: RefundRequest) {
    return this.api.post<RefundResponse>(`/admin/orders/${encodeURIComponent(id)}/refund`, request);
  }
  stuckOrders(query: CursorQuery = {}) {
    return this.api.get<StuckOrderPage>('/admin/orders/stuck', { params: { ...query } });
  }
  adminProblems(query: AdminProblemQuery = {}) {
    return this.api.get<AdminProblemPage>('/admin/problems', { params: { ...query } });
  }
  adminRefundProblem(id: string, request: ResolveProblemRequest | null = null) {
    return this.api.post<ProblemView>(`/admin/problems/${encodeURIComponent(id)}/refund`, request);
  }
  adminDismissProblem(id: string, request: DismissProblemRequest) {
    return this.api.post<ProblemView>(`/admin/problems/${encodeURIComponent(id)}/dismiss`, request);
  }
  replayDeadLetter(queue: string) {
    return this.api.postResponse<void>(
      `/admin/orders/dead-letters/${encodeURIComponent(queue)}/replay`,
    );
  }
}
