import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('renders the skip link pointing at the main landmark', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const skip = root.querySelector('a.skip-link');
    expect(skip?.getAttribute('href')).toBe('#main');
    expect(root.querySelector('main#main')).not.toBeNull();
  });

  it('puts the skip link before the main landmark in document order', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const first = root.querySelector('a, button, input, [tabindex]');
    expect(first?.classList.contains('skip-link')).toBe(true);
  });
});
