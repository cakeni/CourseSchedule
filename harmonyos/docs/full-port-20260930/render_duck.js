async page => {
  await page.evaluate(async () => {
    window.duck.stopRendering();
    window.duck.startRendering = () => {};
    window.duck.reset({ artboard: 'Artboard', autoplay: true, stateMachines: 'State Machine 1' });
    window.duck.stopRendering();
    window.duck.resizeDrawingSurfaceToCanvas(1);
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    window.duck.lastRenderTime = 1000;
    window.duck.frameCount = 0;
  });
  for (let frame = 0; frame < 300; frame++) {
    await page.evaluate(index => {
      window.duck.draw(1000 + index * 1000 / 60);
      window.duck.runtime.resolveAnimationFrame();
      window.duck.stopRendering();
      if (window.duck.frameCount !== index + 1) throw new Error('Rive automatic clock advanced during export');
    }, frame);
    await page.locator('#duck').screenshot({
      path: `entry/src/main/resources/rawfile/duck/${String(frame).padStart(3, '0')}.png`,
      omitBackground: true, timeout: 10000
    });
  }
  return { frames: 300, fps: 60, durationMs: 5000, size: 192 };
}
