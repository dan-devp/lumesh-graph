package dev.lumesh.graph;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.io.PrintWriter;

public class ConsoleOutput implements AutoCloseable {

    private static final int BAR_WIDTH = 28;
    private static final int LOG_INTERVAL = 500;

    private final Terminal terminal;
    private final PrintWriter out;
    private final boolean interactive;

    public ConsoleOutput() {
        Terminal t;
        try {
            t = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            t = null;
        }
        terminal = t;
        out = (terminal != null) ? terminal.writer() : new PrintWriter(System.out, true);
        interactive = terminal != null
            && !Terminal.TYPE_DUMB.equals(terminal.getType())
            && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
    }

    public void info(String msg) {
        out.println(msg);
        out.flush();
    }

    public void success(String msg) {
        out.println(render(new AttributedStringBuilder()
            .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
            .append("✔ " + msg)
            .style(AttributedStyle.DEFAULT)));
        out.flush();
    }

    public void error(String msg) {
        out.println(render(new AttributedStringBuilder()
            .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
            .append("✘ " + msg)
            .style(AttributedStyle.DEFAULT)));
        out.flush();
    }

    public void progress(String label, int done, int total) {
        if (total == 0) return;
        boolean last = done == total;
        if (!last && done % LOG_INTERVAL != 0) return;

        int pct = done * 100 / total;
        int filled = BAR_WIDTH * done / total;

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        sb.append(String.format("  %-22s", label));
        sb.style(AttributedStyle.DEFAULT);
        sb.append(" [");
        sb.style(AttributedStyle.DEFAULT.foreground(last ? AttributedStyle.GREEN : AttributedStyle.YELLOW));
        sb.append("█".repeat(filled));
        sb.append("░".repeat(BAR_WIDTH - filled));
        sb.style(AttributedStyle.DEFAULT);
        sb.append(String.format("] %5d/%-5d %3d%%", done, total, pct));

        String line = render(sb);
        if (interactive) {
            out.print("\r" + line);
            if (last) out.println();
        } else {
            out.println(line);
        }
        out.flush();
    }

    private String render(AttributedStringBuilder sb) {
        return terminal != null ? sb.toAnsi(terminal) : sb.toString();
    }

    @Override
    public void close() {
        if (terminal != null) {
            try { terminal.close(); } catch (IOException ignored) {}
        }
    }
}
