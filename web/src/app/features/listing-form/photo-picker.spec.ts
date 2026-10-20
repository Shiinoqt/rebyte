import { describe, expect, it, vi } from 'vitest';
import { CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { PhotoPipeline, ProcessedPhoto } from '../../core/media/photo-pipeline';
import { PhotoPicker, PhotoSelection } from './photo-picker';

function processed(name: string): ProcessedPhoto {
  return {
    blob: new Blob(['photo'], { type: 'image/jpeg' }),
    width: 20,
    height: 10,
    url: `blob:${name}`,
    cleanup: vi.fn(),
  };
}
function setup() {
  const process = vi.fn(async (file: File) => processed(file.name));
  const selection = new PhotoSelection({ process } as unknown as PhotoPipeline);
  return { selection, process };
}
const file = (name: string, type = 'image/jpeg') => new File(['photo'], name, { type });

describe('ordered listing photos', () => {
  it('keeps the add tile out of drag indices so dragging the first photo changes the cover', async () => {
    const { selection } = setup();
    selection.hydrate(['key-a', 'key-b'], ['https://cdn/a', 'https://cdn/b']);
    TestBed.configureTestingModule({
      imports: [PhotoPicker],
      providers: [
        { provide: PhotoSelection, useValue: selection },
        {
          provide: BreakpointObserver,
          useValue: { observe: () => of({ matches: false, breakpoints: {} }) },
        },
      ],
    });
    try {
      const fixture = TestBed.createComponent(PhotoPicker);
      fixture.detectChanges();
      await fixture.whenStable();
      const list = fixture.debugElement.query(By.directive(CdkDropList)).injector.get(CdkDropList);
      expect(fixture.nativeElement.querySelector('.tiles').firstElementChild.tagName).toBe(
        'BUTTON',
      );
      expect(list.getSortedItems()).toHaveLength(2);
      list.dropped.emit({ previousIndex: 0, currentIndex: 1 } as CdkDragDrop<unknown>);
      fixture.detectChanges();
      expect(selection.items().map((photo) => photo.key)).toEqual(['key-b', 'key-a']);
      expect(fixture.nativeElement.querySelector('.tile img').src).toBe('https://cdn/b');
    } finally {
      TestBed.resetTestingModule();
    }
  });
  it('rejects unsupported files, accepts WebP and reserves at most five slots', async () => {
    const { selection, process } = setup();
    await selection.add([
      file('bad.gif', 'image/gif'),
      file('a.webp', 'image/webp'),
      ...Array.from({ length: 5 }, (_, i) => file(`${i}.jpg`)),
    ]);
    expect(selection.items()).toHaveLength(5);
    expect(process).toHaveBeenCalledTimes(5);
    expect(process).toHaveBeenCalledWith(expect.any(File), { maxEdge: 2000, maxBytes: 2097152 });
    expect(selection.errors().join(' ')).toContain('bad.gif');
    expect(selection.errors().join(' ')).toContain('up to 5');
  });

  it('keeps failures out of the ordered list and frees their slots', async () => {
    const { selection, process } = setup();
    process.mockRejectedValueOnce(new Error('decode'));
    await selection.add([file('broken.jpg'), file('good.png', 'image/png')]);
    expect(selection.items().map((p) => p.name)).toEqual(['good.png']);
    expect(selection.errors().join(' ')).toContain('broken.jpg');
    expect(selection.processing()).toBe(false);
  });

  it('produces the same cover/order by drag indices and arrow indices with stored and local photos', async () => {
    const { selection } = setup();
    selection.hydrate(['key-a', 'key-b'], ['https://cdn/a', 'https://cdn/b']);
    await selection.add([file('new.jpg')]);
    selection.move(2, 0);
    expect(selection.items().map((p) => p.key ?? p.name)).toEqual(['new.jpg', 'key-a', 'key-b']);
    selection.move(0, 1);
    selection.move(1, 2);
    expect(selection.items().map((p) => p.key ?? p.name)).toEqual(['key-a', 'key-b', 'new.jpg']);
  });

  it('cleans local object URLs on removal and destruction without revoking remote URLs', async () => {
    const { selection } = setup();
    selection.hydrate(['stored'], ['https://cdn/stored']);
    await selection.add([file('a.jpg'), file('b.jpg')]);
    const a = selection.items()[1].processed!;
    const b = selection.items()[2].processed!;
    selection.remove(1);
    expect(a.cleanup).toHaveBeenCalledOnce();
    selection.ngOnDestroy();
    expect(b.cleanup).toHaveBeenCalledOnce();
    expect(a.cleanup).toHaveBeenCalledOnce();
  });

  it('cleans a photo finishing after the form was destroyed', async () => {
    const { selection, process } = setup();
    let finish!: (p: ProcessedPhoto) => void;
    process.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    const pending = selection.add([file('late.jpg')]);
    expect(selection.processing()).toBe(true);
    selection.ngOnDestroy();
    const photo = processed('late');
    finish(photo);
    await pending;
    expect(photo.cleanup).toHaveBeenCalledOnce();
    expect(selection.items()).toEqual([]);
  });
});
