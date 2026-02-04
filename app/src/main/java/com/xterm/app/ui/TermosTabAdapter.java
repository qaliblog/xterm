package com.xterm.app.ui;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import com.xterm.app.R;

/**
 * Adapter for terminal tab only (simple terminal, no OS/VNC).
 */
public class TermosTabAdapter extends FragmentPagerAdapter {

    private static final int TAB_COUNT = 1;
    private TerminalTabFragment terminalTabFragment;
    private Context context;

    public TermosTabAdapter(@NonNull FragmentManager fm, int behavior, Context context) {
        super(fm, behavior);
        this.context = context;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        if (terminalTabFragment == null) {
            terminalTabFragment = new TerminalTabFragment();
        }
        return terminalTabFragment;
    }

    @Override
    public int getCount() {
        return TAB_COUNT;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return context != null ? context.getString(R.string.tab_terminal_title) : "";
    }

    public TerminalTabFragment getTerminalTabFragment() {
        return terminalTabFragment;
    }
}

