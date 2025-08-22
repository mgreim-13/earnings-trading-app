import React from 'react';
import { Box, keyframes } from '@mui/material';
import { styled } from '@mui/material/styles';

// Keyframe animations for the dancing money bag
const bounce = keyframes`
  0%, 20%, 50%, 80%, 100% {
    transform: translateY(0) rotate(0deg);
  }
  40% {
    transform: translateY(-20px) rotate(-5deg);
  }
  60% {
    transform: translateY(-10px) rotate(5deg);
  }
`;

const wiggle = keyframes`
  0%, 100% {
    transform: rotate(0deg);
  }
  25% {
    transform: rotate(-3deg);
  }
  75% {
    transform: rotate(3deg);
  }
`;

const sparkle = keyframes`
  0%, 100% {
    opacity: 0;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1.2);
  }
`;

const float = keyframes`
  0%, 100% {
    transform: translateY(0px);
  }
  50% {
    transform: translateY(-8px);
  }
`;

// Styled components
const LoaderContainer = styled(Box)(({ theme }) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  position: 'relative',
  padding: theme.spacing(4),
}));

const MoneyBagContainer = styled(Box)({
  position: 'relative',
  animation: `${bounce} 2s ease-in-out infinite`,
  fontSize: '4rem',
  filter: 'drop-shadow(0 4px 8px rgba(0,0,0,0.2))',
});

const SparkleContainer = styled(Box)({
  position: 'absolute',
  top: '-10px',
  left: '-10px',
  right: '-10px',
  bottom: '-10px',
  pointerEvents: 'none',
});

const Sparkle = styled('div')(({ delay = 0, x = 0, y = 0 }) => ({
  position: 'absolute',
  left: `${50 + x}%`,
  top: `${50 + y}%`,
  transform: 'translate(-50%, -50%)',
  animation: `${sparkle} 1.5s ease-in-out infinite`,
  animationDelay: `${delay}s`,
  fontSize: '1rem',
  color: '#FFD700',
}));

const LoadingText = styled(Box)(({ theme }) => ({
  marginTop: theme.spacing(2),
  animation: `${float} 2s ease-in-out infinite`,
  fontFamily: '"Comic Sans MS", cursive, sans-serif',
  fontSize: '1.1rem',
  fontWeight: 'bold',
  color: theme.palette.primary.main,
  textAlign: 'center',
  textShadow: '1px 1px 2px rgba(0,0,0,0.1)',
}));

const MoneyBagEmoji = styled('div')({
  animation: `${wiggle} 0.5s ease-in-out infinite alternate`,
});

const CuteMoneyBagLoader = ({ 
  size = 'medium', 
  message = 'Loading your money magic...', 
  showSparkles = true 
}) => {
  const sizeMap = {
    small: '2rem',
    medium: '4rem',
    large: '6rem',
  };

  const messages = [
    'Counting your coins... 💰',
    'Preparing your profits... ✨',
    'Loading money magic... 🎩',
    'Calculating gains... 📈',
    'Summoning success... 🌟',
    'Brewing wealth... ⚗️',
    'Gathering golden data... 🏆',
    'Mixing market magic... 🔮',
  ];

  const randomMessage = messages[Math.floor(Math.random() * messages.length)];
  const displayMessage = message === 'Loading your money magic...' ? randomMessage : message;

  return (
    <LoaderContainer>
      <MoneyBagContainer sx={{ fontSize: sizeMap[size] }}>
        <MoneyBagEmoji>💰</MoneyBagEmoji>
        
        {showSparkles && (
          <SparkleContainer>
            <Sparkle delay={0} x={-20} y={-20}>✨</Sparkle>
            <Sparkle delay={0.3} x={20} y={-15}>⭐</Sparkle>
            <Sparkle delay={0.6} x={-15} y={20}>💫</Sparkle>
            <Sparkle delay={0.9} x={15} y={15}>🌟</Sparkle>
            <Sparkle delay={1.2} x={0} y={-25}>✨</Sparkle>
          </SparkleContainer>
        )}
      </MoneyBagContainer>
      
      <LoadingText>
        {displayMessage}
      </LoadingText>
    </LoaderContainer>
  );
};

export default CuteMoneyBagLoader;
