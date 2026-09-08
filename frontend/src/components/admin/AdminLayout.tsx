import { useState } from 'react';
import { Link as RouterLink, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Drawer from '@mui/material/Drawer';
import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import MenuIcon from '@mui/icons-material/Menu';
import DashboardIcon from '@mui/icons-material/GridViewOutlined';
import OrdersIcon from '@mui/icons-material/ReceiptLongOutlined';
import ProductsIcon from '@mui/icons-material/Inventory2Outlined';
import CategoriesIcon from '@mui/icons-material/CategoryOutlined';
import AttributesIcon from '@mui/icons-material/TuneOutlined';
import VendorsIcon from '@mui/icons-material/LocalShippingOutlined';
import EnquiriesIcon from '@mui/icons-material/ForumOutlined';
import CustomersIcon from '@mui/icons-material/PeopleOutlined';
import SettingsIcon from '@mui/icons-material/TuneOutlined';
import PaletteIcon from '@mui/icons-material/PaletteOutlined';
import HelpIcon from '@mui/icons-material/HelpOutlineOutlined';
import StorefrontIcon from '@mui/icons-material/StorefrontOutlined';

import { useAuth } from '../../hooks/useAuth';
import { palette } from '../../theme';

const DRAWER_WIDTH = 232;

const NAV = [
  { label: 'Dashboard', to: '/admin', icon: <DashboardIcon fontSize="small" />, end: true },
  { label: 'Orders', to: '/admin/orders', icon: <OrdersIcon fontSize="small" /> },
  { label: 'Products', to: '/admin/products', icon: <ProductsIcon fontSize="small" /> },
  { label: 'Categories', to: '/admin/categories', icon: <CategoriesIcon fontSize="small" /> },
  { label: 'Attributes', to: '/admin/attributes', icon: <AttributesIcon fontSize="small" /> },
  { label: 'Suppliers', to: '/admin/vendors', icon: <VendorsIcon fontSize="small" /> },
  { label: 'Enquiries', to: '/admin/enquiries', icon: <EnquiriesIcon fontSize="small" /> },
  { label: 'Customers', to: '/admin/customers', icon: <CustomersIcon fontSize="small" /> },
  { label: 'Appearance', to: '/admin/appearance', icon: <PaletteIcon fontSize="small" /> },
  { label: 'Settings', to: '/admin/settings', icon: <SettingsIcon fontSize="small" /> },
  { label: 'Help', to: '/admin/help', icon: <HelpIcon fontSize="small" /> },
];

/**
 * Shell for the back office.
 *
 * <p>Deliberately denser and plainer than the storefront. This is a tool
 * somebody operates for an hour at a time, so it favours scanning and a
 * permanent sidebar over the storefront's generous spacing.
 */
export function AdminLayout() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  async function handleSignOut() {
    await signOut();
    navigate('/admin/login', { replace: true });
  }

  const nav = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Box sx={{ px: 2.5, py: 2.25 }}>
        <Typography
          sx={{
            fontFamily: '"Fraunces", Georgia, serif',
            fontSize: '1.3rem',
            fontWeight: 600,
            lineHeight: 1.1,
          }}
        >
          Rainbow Books
        </Typography>
        <Typography
          sx={{
            fontSize: 10.5,
            letterSpacing: '0.16em',
            textTransform: 'uppercase',
            color: 'text.secondary',
          }}
        >
          Back office
        </Typography>
      </Box>

      <Divider />

      <List sx={{ px: 1, py: 1.5, flexGrow: 1 }}>
        {NAV.map((item) => {
          const active = item.end
            ? location.pathname === item.to
            : location.pathname.startsWith(item.to);
          return (
            <ListItemButton
              key={item.to}
              component={NavLink}
              to={item.to}
              end={item.end}
              onClick={() => setMobileOpen(false)}
              sx={{
                borderRadius: 2,
                mb: 0.25,
                color: active ? 'primary.main' : 'text.primary',
                bgcolor: active ? 'rgba(163,59,46,0.08)' : 'transparent',
                '&:hover': { bgcolor: active ? 'rgba(163,59,46,0.12)' : 'action.hover' },
              }}
            >
              <ListItemIcon sx={{ minWidth: 34, color: 'inherit' }}>{item.icon}</ListItemIcon>
              <ListItemText
                primary={item.label}
                slotProps={{ primary: { sx: { fontSize: 14, fontWeight: active ? 700 : 500 } } }}
              />
            </ListItemButton>
          );
        })}
      </List>

      <Divider />

      <Box sx={{ p: 1.5 }}>
        <ListItemButton
          component={RouterLink}
          to="/"
          sx={{ borderRadius: 2, color: 'text.secondary' }}
        >
          <ListItemIcon sx={{ minWidth: 34, color: 'inherit' }}>
            <StorefrontIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText
            primary="View storefront"
            slotProps={{ primary: { sx: { fontSize: 13 } } }}
          />
        </ListItemButton>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100dvh', bgcolor: 'background.default' }}>
      <AppBar
        position="fixed"
        sx={{
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          ml: { md: `${DRAWER_WIDTH}px` },
          bgcolor: 'rgba(251,247,240,0.9)',
          backdropFilter: 'blur(10px)',
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Toolbar sx={{ gap: 1.5, minHeight: { xs: 56, md: 60 } }}>
          <IconButton
            onClick={() => setMobileOpen(true)}
            sx={{ display: { md: 'none' } }}
            aria-label="Open navigation"
          >
            <MenuIcon />
          </IconButton>

          <Box sx={{ flexGrow: 1 }} />

          {user && (
            <Chip
              size="small"
              label={user.email}
              sx={{ display: { xs: 'none', sm: 'inline-flex' }, maxWidth: 260 }}
            />
          )}
          {/* The screen existed and worked, but nothing linked to it: an
              administrator could only reach it by being forced there at first
              sign-in, or by typing the URL. */}
          <Button size="small" component={RouterLink} to="/admin/change-password">
            Change password
          </Button>
          <Button size="small" onClick={handleSignOut}>
            Sign out
          </Button>
        </Toolbar>
      </AppBar>

      <Box component="nav" sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}>
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          slotProps={{ paper: { sx: { width: DRAWER_WIDTH, bgcolor: palette.ivory } } }}
          sx={{ display: { xs: 'block', md: 'none' } }}
        >
          {nav}
        </Drawer>

        <Drawer
          variant="permanent"
          open
          slotProps={{
            paper: {
              sx: {
                width: DRAWER_WIDTH,
                bgcolor: palette.ivory,
                borderRight: 1,
                borderColor: 'divider',
              },
            },
          }}
          sx={{ display: { xs: 'none', md: 'block' } }}
        >
          {nav}
        </Drawer>
      </Box>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          pt: { xs: 8, md: 9 },
          px: { xs: 2, sm: 3 },
          pb: 6,
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
}
