package com.github.gcd888.guoju.ui.adapter;

import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import com.github.gcd888.guoju.base.BaseLazyFragment;

import java.util.List;

/**
 * @user acer
 * @date 2018/12/4
 */

public class HomePageAdapter extends FragmentPagerAdapter {
    public FragmentManager fragmentManager;
    public List<BaseLazyFragment> list;
    private final long adapterId = System.nanoTime();

    public HomePageAdapter(FragmentManager fm) {
        super(fm);
    }

    public HomePageAdapter(FragmentManager fm, List<BaseLazyFragment> list) {
        super(fm);
        this.fragmentManager = fm;
        this.list = list;
    }

    public void clear() {
        list.clear();
        notifyDataSetChanged();
    }

    public void removeAll() {
        if (list == null || fragmentManager == null) return;
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        for (BaseLazyFragment fragment : list) {
            if (fragment != null && fragment.isAdded()) {
                transaction.remove(fragment);
            }
        }
        transaction.commitAllowingStateLoss();
        fragmentManager.executePendingTransactions();
        list.clear();
        notifyDataSetChanged();
    }

    @Override
    public Fragment getItem(int position) {
        return list.get(position);
    }

    @Override
    public long getItemId(int position) {
        return adapterId + position;
    }

    @Override
    public int getCount() {
        return list != null ? list.size() : 0;
    }

    @Override
    public Fragment instantiateItem(ViewGroup container, int position) {
        Fragment fragment = (Fragment) super.instantiateItem(container, position);
        fragmentManager.beginTransaction().show(fragment).commitAllowingStateLoss();
        return fragment;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        // 恢复标准行为：销毁页面时真正 detach 视图。此前仅 hide()，页面视图会残留，
        // 整页重建的布局空档里可能把未布局的货架（gravity=bottom，几何在父容器原点）画到屏幕左上角
        super.destroyItem(container, position, object);
    }
}
