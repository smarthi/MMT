import threading
import time

import sys


class Progressbar:
    def __init__(self, label=None, bar_length=40, refresh_time_in_seconds=1):
        self.label = label

        self._refresh_timeout = refresh_time_in_seconds
        self._bar_length = bar_length

        self._start_time = None
        self._progress = 0.0
        self._background_thread = None

    def _timer_handle(self):
        self._update()
        self._background_thread = threading.Timer(self._refresh_timeout, self._timer_handle)
        self._background_thread.start()

    def _update(self, newline=False):
        elapsed = time.time() - self._start_time
        filled_len = int(self._bar_length * self._progress)
        percents = round(100.0 * self._progress, 1)

        prefix = self.label + '... ' if self.label is not None else ''
        bar = '=' * filled_len + ' ' * (self._bar_length - filled_len)
        percents_text = ('%.1f%%' % percents).rjust(6)
        elapsed_text = '%02d:%02d' % (int(elapsed / 60), elapsed % 60)

        sys.stdout.write('%s[%s] %s %s\r' % (prefix, bar, percents_text, elapsed_text))
        if newline:
            sys.stdout.write('\n')
        sys.stdout.flush()

    def start(self):
        self._start_time = time.time()
        self._timer_handle()

    def set_progress(self, progress):
        self._progress = progress

    def cancel(self):
        self._background_thread.cancel()

    def complete(self):
        self._background_thread.cancel()
        self._progress = 1.0
        self._update(newline=True)
